# deploy

The Docker Compose stack of vibefix (AD-26, AD-32) and its checks. The same files run the stack on
a laptop and in CI (`.github/workflows/ci.yml`).

```text
compose.yaml              the stack: caddy, app, sandbox, egress, postgres
caddy/Dockerfile          image vibefix-web: the SPA built with Node, served by Caddy
caddy/Caddyfile           one origin: SPA + /api and /webhooks proxied to app; headers, 15 MB cap
postgres/initdb/          creates the roles and the database on an empty volume
egress/squid.conf         the sandbox's egress proxy: CONNECT 443 to GitHub only
dev-secrets.sh            creates the local secret files in deploy/secrets/ (ignored by git)
smoke.sh                  asserts the stack's behaviour (Story 1.2's I/O matrix)
zap/rules.tsv             OWASP ZAP baseline: the alerts ignored, each with its reason
```

The application image `vibefix-app` (`backend/Dockerfile`) runs twice: as `app` (profile `dev`)
and as `sandbox` (profile `sandbox`, which starts only the sandbox worker).

| Service | Networks | Notes |
|---|---|---|
| `caddy` | `edge` | the only published port: `127.0.0.1:8000` |
| `app` | `edge`, `data`, `jobs` | uid 10001; reads two secret files; JSON logs |
| `sandbox` | `jobs` | uid 10001; no secrets, no database, read-only root, 256 MB `/tmp`, 768 MB, 1 CPU, 256 processes |
| `egress` | `jobs`, `outbound` | squid; the only way from `jobs` to the internet, and only to GitHub |
| `postgres` | `data` | never published; volume `pgdata` at `/var/lib/postgresql` |

`data` and `jobs` are internal networks: nothing on them reaches the internet directly. Every
container runs with `TZ=UTC`, and none mounts the Docker socket.

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

The OWASP ZAP baseline that CI runs, against the running stack:

```sh
MSYS_NO_PATHCONV=1 docker run --rm --network vibefix_edge -v "$PWD/deploy/zap:/zap/wrk:rw" \
  ghcr.io/zaproxy/zaproxy:2.17.0@sha256:5ccfff9a411743ab2615136a2edd7416bec9ce41572fa94e935df38c14adb2b6 \
  zap-baseline.py -t http://caddy -c rules.tsv
```

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
| `backend` | `./mvnw -B verify` (unit tests, Modulith verification, integration tests on Testcontainers), then `git diff --exit-code` (a stale `openapi.json` fails) |
| `frontend` | `npm ci`, `npm run lint`, `npm run build` |
| `secrets` | gitleaks over the whole git history |
| `zap` | this stack with throwaway secrets, `smoke.sh`, then the ZAP baseline against `http://caddy` |
| `publish` | only on a push to `main`, only after all four passed: `ghcr.io/<owner>/vibefix-app` and `vibefix-web`, tagged with the commit SHA, for `linux/amd64` and `linux/arm64` |

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
     `Secret scan (gitleaks, full history)` and `Stack smoke test and OWASP ZAP baseline`
     (they appear in the list after CI has run once).
4. Leave the bypass list empty, then **Create**.

Also, under **Settings > Actions > General**: **Workflow permissions: Read repository contents and
packages permissions** (the workflow asks for `packages: write` itself, in `publish` only), and
allow only actions pinned to a full-length commit SHA if the option is offered.
