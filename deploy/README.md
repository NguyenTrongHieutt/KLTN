# deploy

The local development stack of vibefix: three services, each published on localhost only.

| Service | Address | What it is |
|---|---|---|
| `frontend` | <http://localhost:5173> | Vite dev server on `frontend/` (Node 24), hot reload; `/api` is proxied to `backend` |
| `backend` | <http://localhost:8080> | Spring Boot, profile `dev`; Swagger UI at `/swagger-ui/index.html`, health at `/actuator/health` |
| `postgres` | `localhost:5432` | PostgreSQL 18, database `vibefix`, volume `pgdata` |

```text
compose.yaml          the three services
postgres/initdb/      creates the roles and the database on an empty volume
dev-secrets.sh        creates the local secret files in deploy/secrets/ (ignored by git)
later/                the production shape and CI, parked until the dev environment works (below)
```

## Start and stop

Requirements: Docker Desktop with Compose v2, and Git Bash on Windows. From the repository root:

```sh
bash deploy/dev-secrets.sh                                     # once; keeps existing files
docker compose -f deploy/compose.yaml up -d --build --wait
```

The first start takes a few minutes (Maven build, `npm ci`). Then:

- Frontend changes reload in the browser by themselves (the container polls the mounted folder).
- Backend changes need a rebuild: `docker compose -f deploy/compose.yaml up -d --build backend`.
- After `package-lock.json` changes, restart the frontend: `docker compose -f deploy/compose.yaml restart frontend`
  (it runs `npm ci` again only then).
- Logs: `docker compose -f deploy/compose.yaml logs -f backend` (or `frontend`, `postgres`).

Stop with `docker compose -f deploy/compose.yaml down`; add `--volumes` to delete the database and
the frontend's `node_modules` volume.

## Connecting to the database

Host `localhost`, port `5432`, database `vibefix` (DBeaver, pgAdmin, psql). The passwords are the
files in `deploy/secrets/` (`cat deploy/secrets/db-app-password`):

| User | Password file | Use it for |
|---|---|---|
| `vibefix_app` | `db-app-password` | what the application sees: data only (SELECT, INSERT, UPDATE, DELETE) |
| `vibefix_migrator` | `db-migrator-password` | owns every schema; Flyway migrates as this role |
| `postgres` | `db-superuser-password` | superuser; vibefix never uses it |

Change the schema only through Flyway migrations (`backend/src/main/resources/db/migration`), never
by hand: `MigrationConventionsTest` and the application's validation compare against them.

## Secret files

Database credentials are files, never environment variables (AD-22). Compose mounts each file at
`/run/secrets/<name>`, and the application refuses to start when one of its secret properties is
set as an environment variable or a JVM system property. `dev-secrets.sh` creates the files with
random values and keeps existing ones, because the database volume already holds those; `--force`
replaces them, after which the volume must be deleted too (`down --volumes`). Never commit a secret
file: `secrets/` is in `.gitignore`.

## Signing in with GitHub locally

Sign-in uses a GitHub OAuth App of the dev environment (AD-10, AD-25). Until it exists, the two
files `secrets/github-oauth-client-id` and `secrets/github-oauth-client-secret` stay empty, and the
"Đăng nhập với GitHub" button on http://localhost:5173/dang-nhap answers that sign-in is not set up.

1. On GitHub, under the `vibefix-dev` organization's Settings › Developer settings › OAuth Apps (or
   your own account's, while that organization does not exist yet), choose New OAuth App:
   - Homepage URL: `http://localhost:5173`
   - Authorization callback URL: `http://localhost:5173/login/oauth2/code/github`
2. Copy the Client ID into `secrets/github-oauth-client-id`, then generate a client secret and copy
   it into `secrets/github-oauth-client-secret`. Write each value without a trailing newline (the
   files are read-only: `chmod 644` them first, and `chmod 444` after).
3. Restart the backend: `docker compose -f deploy/compose.yaml up -d --force-recreate backend`.

The app asks only for `read:user` and `user:email` (FR-1). vibefix keeps the token of a Customer
only, encrypted with `secrets/identity-token-key-1`, which `dev-secrets.sh` creates; deleting that
key makes every kept token unreadable. To try a locked account, set it in the database:
`UPDATE identity.account SET locked_at = now(), lock_reason = '...' WHERE github_login = '...'`.

## Parked for later (`later/`)

Until the application works in this dev environment, the stack leaves out the production shape and
CI runs nothing. `later/` keeps the files, and git keeps the full stack (`git show
4c64ceb:deploy/compose.yaml`):

```text
later/ci.yml            the CI workflow: build and tests, gitleaks, smoke test + OWASP ZAP, actionlint,
                        image publication to GHCR (move it back to .github/workflows/ to run it)
later/caddy/            image vibefix-web: the built SPA behind Caddy, security headers, 15 MB cap
later/egress/           image vibefix-egress: Squid, the only way out for sandbox and checker
later/smoke.sh          checks of the full stack (networks, isolation, headers)
later/zap/rules.tsv     OWASP ZAP baseline: the alerts ignored, each with its reason
later/root.dockerignore the repository root's .dockerignore, for the vibefix-web build
```

These files still name their old paths (`deploy/caddy`, `deploy/egress`, `deploy/smoke.sh`); they
work again once moved back. They return in Epic 18, after checkpoint 1 and before the production
host (Story 13.1): CI in Story 18.1, `sandbox` and `egress` in 18.2, `checker` in 18.3, and the
Caddy edge with the ZAP baseline in 18.4. Until then, work the architecture puts in the sandbox
(image re-encoding, repository scans, Git pushes, ZIPs, preview checks) runs in-process through
`SandboxJobs` on this stack; the `prod` profile refuses that mode. Use test repositories and files
only.
