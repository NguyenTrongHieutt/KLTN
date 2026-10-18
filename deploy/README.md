# deploy

The Docker Compose stack of vibefix (AD-26, AD-32) and its checks. The same files run the stack on
a laptop and in CI (`.github/workflows/ci.yml`).

```text
compose.yaml              the stack: caddy, app, sandbox, checker, egress, postgres
caddy/Dockerfile          image vibefix-web: the SPA built with Node, served by Caddy
caddy/Caddyfile           one origin: SPA + /api and /webhooks proxied to app; headers, 15 MB cap
postgres/initdb/          creates the roles and the database on an empty volume
egress/Dockerfile         image vibefix-egress: Squid from Ubuntu 24.04 (pinned by digest), non-root
egress/squid.conf         two listeners: sandbox to GitHub only (3128), checker to public hosts (3129)
dev-secrets.sh            creates the local secret files in deploy/secrets/ (ignored by git)
smoke.sh                  asserts the stack's behaviour (Story 1.2's I/O matrix)
zap/rules.tsv             OWASP ZAP baseline: the alerts ignored, each with its reason
```

The application image `vibefix-app` (`backend/Dockerfile`) runs three times: as `app` (profile
`dev`), and as `sandbox` and `checker` (profile `sandbox`, which starts only the sandbox worker).
`checker` runs outbound URL checks (AD-32, AD-35) and serves only the job types listed in
`vibefix.sandbox.worker.job-types`, set on its command line: for now `HEALTH`, the job channel's
probe, until the outbound-check handler exists.

| Service | Networks | Notes |
|---|---|---|
| `caddy` | `edge` | the only published port: `127.0.0.1:8000` |
| `app` | `edge`, `data`, `jobs`, `checks` | uid 10001; reads two secret files; JSON logs |
| `sandbox` | `jobs` (172.16.238.10) | uid 10001; no secrets, no database, read-only root, 256 MB `/tmp`; 1 GB memory (heap at most half, since the tmpfs counts against the limit), 1 CPU, 256 processes; JVM proxy options (egress:3128) on its command line; JSON logs |
| `checker` | `checks` (172.16.239.10) | as `sandbox`, with 64 MB `/tmp`, 512 MB memory, half a CPU, 128 processes; proxy egress:3129; only the listed job types |
| `egress` | `jobs`, `checks`, `outbound` | Squid from Ubuntu 24.04 (`egress/Dockerfile`), user `proxy`, read-only root; the only way from `jobs` and `checks` to the internet; logs to its output |
| `postgres` | `data` | never published; volume `pgdata` at `/var/lib/postgresql` |

`data`, `jobs` and `checks` are internal networks: nothing on them reaches the internet directly.
`sandbox` and `checker` never share a network, so neither can call the other. Every container runs
with `TZ=UTC`, none mounts the Docker socket, and Docker rotates every container's log
(`json-file`, at most 5 files of 10 MB). Dependabot (`.github/dependabot.yml`) proposes minor and
patch updates of the pinned images, actions and packages every week; major versions follow the
architecture spine and are ignored.

### Egress

`egress` opens HTTPS tunnels (`CONNECT` to port 443) and refuses everything else with 403. Each
listener admits a single client address, fixed in `compose.yaml` (the `jobs` and `checks` subnets
lie in 172.16.0.0/16, outside Docker's default address pools):

- **3128, `sandbox` only:** github.com, githubusercontent.com and their subdomains.
- **3129, `checker` only:** any host, after Squid has resolved the name itself and refused it if any
  address is private, loopback, link-local (including the 169.254.169.254 metadata address), shared
  (100.64.0.0/10) or otherwise not public. Squid then connects to the address it checked.

The base image is pinned by digest, but Squid is installed from Ubuntu's security pocket at build
time. CI therefore rebuilds every image without the layer cache once a week (Monday 03:23 UTC, or by
hand with "Run workflow"), runs every gate on the result, and publishes the rebuilt egress image as
`vibefix-egress:<commit>-rebuild-<UTC time>` for `linux/amd64` and `linux/arm64`. The run's summary
lists its digest and the Squid version on each architecture. A push to `main` publishes
`vibefix-egress:<commit>` with the other images.

## Running locally

Requirements: Docker with Compose v2, and bash (Git Bash on Windows). From the repository root:

```sh
bash deploy/dev-secrets.sh
docker compose -f deploy/compose.yaml up -d --build --wait
bash deploy/smoke.sh
```

Then open <http://localhost:8000>. The API is at <http://localhost:8000/api/v1/...>; Actuator,
`/v3/api-docs` and Swagger UI are not reachable through Caddy (use
`docker compose -f deploy/compose.yaml exec app ...` or a container on the `vibefix_edge` network).

Stop the stack with `docker compose -f deploy/compose.yaml down`; add `--volumes` to delete the
database. On Windows, Git Bash rewrites arguments that look like paths: set `MSYS_NO_PATHCONV=1`
for a `docker run -v ...` typed by hand (`smoke.sh` sets it itself).

The OWASP ZAP baseline that CI runs, against the running stack (exit code 0 means no alert beyond
the ones `zap/rules.tsv` ignores):

```sh
MSYS_NO_PATHCONV=1 docker run --rm --network vibefix_edge -v "$PWD/deploy/zap:/zap/wrk:rw" \
  ghcr.io/zaproxy/zaproxy:2.17.0@sha256:5ccfff9a411743ab2615136a2edd7416bec9ce41572fa94e935df38c14adb2b6 \
  zap-baseline.py -t http://caddy -c rules.tsv
```

ZAP runs as uid 1000 and writes its plan (`zap.yaml`, ignored by git) next to the rules, so on
Linux the directory must be writable for that user; CI copies `rules.tsv` to a temporary directory
instead.

## Production host (later)

This stack is the local and CI shape. The production deployment (AD-26) arrives with the
deployment work and adds what a public host needs: HTTPS, with `VIBEFIX_SITE_ADDRESS` set to the
domain, port 443 published and a `caddy_data` volume for the certificates; and a firewall rule
that blocks the instance metadata address (169.254.169.254) for every container.

## Secret files

Platform credentials are files, never environment variables or database rows (AD-22). Compose
mounts each file at `/run/secrets/<name>`, and the application reads that directory as a Spring
configuration tree (`spring.config.import: optional:configtree:/run/secrets/`). The application
refuses to start when one of its secret properties is set as an environment variable or a JVM
system property.

| File | Used by |
|---|---|
| `db-superuser-password` | `postgres` (the `postgres` superuser; vibefix never uses it) |
| `db-app-password` | `postgres` (init), `app` (the runtime role `vibefix_app`) |
| `db-migrator-password` | `postgres` (init), `app` (Flyway, as `vibefix_migrator`) |

`dev-secrets.sh` creates them with random values, and keeps existing ones (the database volume
already holds those); `--force` replaces them, after which the volume must be deleted too.

**Permissions.** Compose bind-mounts a secret file with the host file's owner and mode, so the
file must be readable by the container user that needs it: uid 999 in `postgres`, uid 10001 in
`app`. `dev-secrets.sh` therefore makes the directory `0700` (only its owner can open it on the
host) and each file `0444`. On a production host, keep the directory `0700` and owned by the
deploying user, or give each file to its one reader (`chown 10001 db-app-password` and
`chmod 0400`) once PostgreSQL has initialized its volume. The `sandbox` service mounts no secret
at all, and its `HEALTH` job reports the number of files it can see under `/run/secrets` (zero,
or `/actuator/health/sandbox` on `app` is DOWN).

Never commit a secret file: `secrets/` is in `.gitignore`, and CI's gitleaks scan fails on any
credential in the history.

## CI and image publication

`.github/workflows/ci.yml` runs on every push and pull request:

| Job | Gate |
|---|---|
| `backend` | `./mvnw -B verify` (unit tests, Modulith verification, integration tests on Testcontainers), then a clean `git status` (a stale `openapi.json` or any untracked output fails) |
| `frontend` | `npm ci`, `npm run lint`, `npm run build` |
| `secrets` | gitleaks over the whole git history |
| `zap` | this stack with throwaway secrets, `smoke.sh`, then two ZAP baselines: the SPA (`http://caddy`) and the API (`http://caddy/api/v1/environment`); the HTML reports are kept as the `zap-report` artifact |
| `workflow-lint` | actionlint (with shellcheck) on the workflows |
| `publish` | only on a push to `main`, only after all five passed: `ghcr.io/<owner>/vibefix-app` and `vibefix-web`, tagged with the commit SHA, for `linux/amd64` and `linux/arm64`; the run summary records both digests |

Actions are pinned by commit SHA and images by digest. CI holds no production secret; the only
credential is the workflow's `GITHUB_TOKEN`, which only `publish` may use to write packages.

## Protecting `main` (GitHub settings, done by the author)

In the repository on GitHub: **Settings > Rules > Rulesets > New ruleset > New branch ruleset**.

1. Name `main`, **Enforcement status: Active**.
2. **Target branches > Add target > Include default branch**.
3. Turn on:
   - **Restrict deletions**
   - **Block force pushes**
   - **Require a pull request before merging** (required approvals: 0 for a one-person project;
     turn on **Require conversation resolution before merging**)
   - **Require status checks to pass**, with **Require branches to be up to date before merging**,
     adding the checks `Backend build, tests, Modulith`, `Frontend lint and build`,
     `Secret scan (gitleaks, full history)`, `Stack smoke test and OWASP ZAP baseline` and
     `Workflow lint (actionlint)` (they appear in the list after CI has run once).
4. Leave the bypass list empty, then **Create**.

Also, under **Settings > Actions > General**: **Workflow permissions: Read repository contents and
packages permissions** (the workflow asks for `packages: write` itself, in `publish` only), and
allow only actions pinned to a full-length commit SHA if the option is offered.
