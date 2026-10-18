#!/usr/bin/env bash
# Asserts the stack rows of Story 1.2's I/O matrix against the running stack (deploy/compose.yaml):
# health and published ports, the SPA's and the API's security headers, the 15 MB body cap, the
# paths Caddy never proxies, the sandbox round trip, isolation and egress, UTC and no Docker socket
# everywhere, a secret from an environment variable refused, and JSON logs without secrets.
#
# Requests run in the pinned curl image on the stack's own networks, exactly where the services see
# them. Run from anywhere:  bash deploy/smoke.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# Git Bash on Windows would rewrite arguments that look like paths.
export MSYS_NO_PATHCONV=1

readonly PROJECT=vibefix
readonly CURL_IMAGE=curlimages/curl:8.23.0@sha256:a134550be58ab783810e613ae96fd253c38f7bdd16d9bf9be350700c7a29ac06
readonly CSP="default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
readonly HSTS="max-age=31536000; includeSubDomains"
readonly REFERRER="strict-origin-when-cross-origin"
readonly PERMISSIONS="camera=(), geolocation=(), microphone=(), payment=(), usb=()"
readonly UUID_RE='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
readonly SERVICES="app caddy egress postgres sandbox"

failures=0
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ok() { echo "ok    $*"; }
fail() {
	echo "FAIL  $*" >&2
	failures=$((failures + 1))
}
# check "description" command...: ok when the command succeeds.
check() {
	local description=$1
	shift
	if "$@"; then ok "$description"; else fail "$description"; fi
}

# not COMMAND...: succeeds when COMMAND fails.
not() { ! "$@"; }
quietly() { "$@" >/dev/null 2>&1; }
positive() {
	local value
	for value; do [[ $value -gt 0 ]] || return 1; done
}
only_json_lines() { not grep -qv '^{' "$1"; }
# A response from the SPA (Caddy's file server), not from Spring.
is_spa_page() { grep -qi '^Content-Type: text/html' <<<"$1" && not grep -qi '^X-Correlation-Id:' <<<"$1"; }

compose() { docker compose -f compose.yaml "$@"; }
# curl_on NETWORK ARGS...: curl inside the stack network vibefix_NETWORK.
curl_on() {
	local network=$1
	shift
	docker run --rm --network "${PROJECT}_${network}" "$CURL_IMAGE" "$@"
}
container_of() { compose ps --quiet "$1"; }
# Header block of a response, without carriage returns.
headers_of() { tr -d '\r' <<<"$1" | sed -n '1,/^$/p'; }
# has_header HEADERS NAME VALUE: the header NAME (any case) has exactly VALUE.
has_header() {
	local line
	while IFS= read -r line; do
		if [[ "${line,,}" == "${2,,}: "* && "${line:${#2}+2}" == "$3" ]]; then
			return 0
		fi
	done <<<"$1"
	return 1
}
isolated() {
	has_header "$1" Cross-Origin-Opener-Policy same-origin &&
		has_header "$1" Cross-Origin-Embedder-Policy require-corp &&
		has_header "$1" Cross-Origin-Resource-Policy same-origin
}
header_count() { grep -ci -- "^$2:" <<<"$1" || true; }

echo "== Stack up: every service healthy, only caddy published (127.0.0.1:8000)"
for service in $SERVICES; do
	health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$(container_of "$service")" 2>/dev/null || echo missing)"
	check "$service is healthy ($health)" test "$health" = healthy
done
published="$(docker ps --filter "label=com.docker.compose.project=$PROJECT" \
	--format '{{.Label "com.docker.compose.service"}} {{.Ports}}' | while read -r service ports; do
	grep -oE '[^ ,]+->[^ ,]+' <<<"$ports" | sed "s/^/$service /" || true
done)"
check "only caddy publishes a port, at 127.0.0.1:8000 (got: ${published//$'\n'/; })" \
	test "$published" = "caddy 127.0.0.1:8000->80/tcp"

echo "== Every container: TZ=UTC, no Docker socket; app and sandbox run as uid 10001"
for service in $SERVICES; do
	id="$(container_of "$service")"
	check "$service has TZ=UTC" grep -qx 'TZ=UTC' <<<"$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id")"
	check "$service mounts no Docker socket" \
		not grep -q docker.sock <<<"$(docker inspect --format '{{range .Mounts}}{{.Source}} {{.Destination}}{{println}}{{end}}' "$id")"
done
for service in app sandbox; do
	check "$service runs as 10001:10001" test "$(docker inspect --format '{{.Config.User}}' "$(container_of "$service")")" = "10001:10001"
done

echo "== SPA headers (Caddy)"
spa="$(curl_on edge -sS -D - -o /dev/null http://caddy/ || true)"
spa_headers="$(headers_of "$spa")"
check "GET / is 200" grep -qE '^HTTP/[0-9.]+ 200' <<<"$spa_headers"
check "SPA: Content-Security-Policy" has_header "$spa_headers" Content-Security-Policy "$CSP"
check "SPA: Strict-Transport-Security" has_header "$spa_headers" Strict-Transport-Security "$HSTS"
check "SPA: Referrer-Policy" has_header "$spa_headers" Referrer-Policy "$REFERRER"
check "SPA: Permissions-Policy" has_header "$spa_headers" Permissions-Policy "$PERMISSIONS"
check "SPA: X-Content-Type-Options nosniff" has_header "$spa_headers" X-Content-Type-Options nosniff
check "SPA: cross-origin isolation (COOP, COEP, CORP)" isolated "$spa_headers"
check "SPA: no Server header" test "$(header_count "$spa_headers" Server)" = 0
check "SPA deep link falls back to index.html" \
	grep -qE '^HTTP/[0-9.]+ 200' <<<"$(curl_on edge -sS -D - -o /dev/null http://caddy/jobs/123)"

echo "== API headers (Spring, through Caddy)"
api="$(curl_on edge -sS -D - http://caddy/api/v1/environment || true)"
api_headers="$(headers_of "$api")"
api_body="$(tr -d '\r' <<<"$api" | sed '1,/^$/d')"
check "GET /api/v1/environment is 200" grep -qE '^HTTP/[0-9.]+ 200' <<<"$api_headers"
check "API body is {\"name\":\"dev\"} (got $api_body)" test "$api_body" = '{"name":"dev"}'
check "API: Content-Security-Policy, once" test "$(header_count "$api_headers" Content-Security-Policy)" = 1
check "API: Content-Security-Policy" has_header "$api_headers" Content-Security-Policy "$CSP"
check "API: Strict-Transport-Security" has_header "$api_headers" Strict-Transport-Security "$HSTS"
check "API: Referrer-Policy" has_header "$api_headers" Referrer-Policy "$REFERRER"
check "API: Permissions-Policy" has_header "$api_headers" Permissions-Policy "$PERMISSIONS"
check "API: X-Content-Type-Options nosniff" has_header "$api_headers" X-Content-Type-Options nosniff
check "API: cross-origin isolation (COOP, COEP, CORP)" isolated "$api_headers"
check "API: X-Correlation-Id is a UUID" grep -qiE "^X-Correlation-Id: $UUID_RE\$" <<<"$api_headers"
check "API: no Server header" test "$(header_count "$api_headers" Server)" = 0

echo "== Body cap: 16 MB through Caddy"
status="$(docker run --rm --network "${PROJECT}_edge" --entrypoint sh "$CURL_IMAGE" -c \
	'head -c 16777216 /dev/zero | curl -sS -o /dev/null -w "%{http_code}" -X POST \
		-H "Content-Type: application/octet-stream" --data-binary @- http://caddy/api/v1/environment' || true)"
check "a 16 MB POST to /api is 413 (got $status)" test "$status" = 413

echo "== Never proxied: /actuator, /v3, /swagger-ui reach the SPA only"
for path in /actuator/health /actuator/env /v3/api-docs /swagger-ui/index.html; do
	headers="$(headers_of "$(curl_on edge -sS -D - -o /dev/null "http://caddy$path")")"
	check "$path is the SPA (HTML, no X-Correlation-Id)" \
		is_spa_page "$headers"
done

echo "== Sandbox round trip"
sandbox_health="$(curl_on edge -sS http://app:8080/actuator/health/sandbox || true)"
check "app /actuator/health/sandbox is UP (got $sandbox_health)" test "$sandbox_health" = '{"status":"UP"}'
nonce="smoke-$RANDOM-$RANDOM"
job="$(curl_on jobs -sS -X POST -H 'Content-Type: application/json' \
	-d "{\"type\":\"HEALTH\",\"input\":{\"nonce\":\"$nonce\"}}" http://sandbox:8080/internal/sandbox/jobs || true)"
check "HEALTH job echoes the nonce ($job)" grep -qF "\"nonce\":\"$nonce\"" <<<"$job"
check "HEALTH job sees no secret files" grep -qF '"secretFiles":0' <<<"$job"
check "HEALTH job sees no datasource" grep -qF '"datasourceConfigured":false' <<<"$job"

echo "== Sandbox isolation"
sandbox_id="$(container_of sandbox)"
check "sandbox root filesystem is read-only" test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$sandbox_id")" = true
read -r memory nano_cpus pids <<<"$(docker inspect --format \
	'{{.HostConfig.Memory}} {{.HostConfig.NanoCpus}} {{.HostConfig.PidsLimit}}' "$sandbox_id")"
check "sandbox has memory, CPU and process limits ($memory bytes, $nano_cpus nano-CPUs, $pids processes)" \
	positive "${memory:-0}" "${nano_cpus:-0}" "${pids:-0}"
check "sandbox /tmp is a size-capped tmpfs" grep -q 'size=' <<<"$(docker inspect --format '{{index .HostConfig.Tmpfs "/tmp"}}' "$sandbox_id")"
check "sandbox mounts nothing (no secrets)" test -z "$(docker inspect --format '{{range .Mounts}}{{.Destination}} {{end}}' "$sandbox_id")"
check "sandbox is only on the jobs network" \
	test "$(docker inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' "$sandbox_id")" = "${PROJECT}_jobs "
check "sandbox environment holds no database settings or secrets" \
	not grep -qiE "PASSWORD|SECRET|DATASOURCE|VIBEFIX_DB" <<<"$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$sandbox_id")"

echo "== Sandbox egress (from the jobs network)"
github="$(curl_on jobs -sS -o /dev/null -w '%{http_connect} %{http_code}' --max-time 30 -x http://egress:3128 https://github.com/ || true)"
check "github.com through egress is allowed (CONNECT/HTTP: $github)" grep -qE '^200 [23][0-9][0-9]$' <<<"$github"
example="$(curl_on jobs -sS -o /dev/null -w '%{http_connect}' --max-time 30 -x http://egress:3128 https://example.com/ 2>/dev/null || true)"
check "example.com through egress is refused (CONNECT: $example)" test "$example" = 403
# curl itself must fail to resolve (6), connect (7) or finish in time (28); a docker failure does not count.
direct=0
quietly curl_on jobs -sS -o /dev/null --max-time 10 https://example.com/ || direct=$?
check "example.com direct is unreachable (curl exit $direct)" grep -qxE '6|7|28' <<<"$direct"

echo "== A secret from an environment variable refuses the start (AD-22)"
run_exit=0
timeout 180 docker compose -f compose.yaml run --rm --no-deps -e SPRING_DATASOURCE_PASSWORD=from-the-environment app \
	>"$tmp/refused.log" 2>&1 || run_exit=$?
# 124: timeout stopped it, so the application did not refuse to start.
check "app with SPRING_DATASOURCE_PASSWORD exits with an error within 180 s (exit $run_exit)" \
	grep -qvxE '0|124' <<<"$run_exit"
check "the refusal names the property" grep -q "spring.datasource.password is set in an environment variable" "$tmp/refused.log"
check "the refusal never shows the value" not grep -q from-the-environment "$tmp/refused.log"

echo "== Logs: JSON lines, no secret values"
for service in app sandbox; do
	compose logs --no-color --no-log-prefix "$service" >"$tmp/$service.log"
	check "$service has logged something" test -s "$tmp/$service.log"
	check "every $service log line is a JSON object" only_json_lines "$tmp/$service.log"
done
compose logs --no-color --no-log-prefix >"$tmp/all.log"
for name in db-superuser-password db-app-password db-migrator-password; do
	check "no log contains the value of $name" not grep -qF -- "$(cat "secrets/$name")" "$tmp/all.log"
done

echo
if [[ $failures -gt 0 ]]; then
	echo "smoke: $failures check(s) failed" >&2
	exit 1
fi
echo "smoke: all checks OK"
