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
work again once moved back. The sandbox worker (AD-32) has to come back before the first story that
runs a job in it: Story 1.13 (student card images are re-encoded in the sandbox), then Story 2.3
(repository scans). Bring CI back before production (Story 13-1) at the latest.
