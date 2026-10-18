# vibefix

The vibefix platform: one Spring Boot modular monolith (`backend/`) and a React single-page app
(`frontend/`). The architecture spine, PRD and UX documents live outside this repository; this
README covers only how to build and run the code.

## Layout

```text
backend/    Maven, Spring Boot 4.1, Java 25, Spring Modulith; profiles dev, prod, sandbox (cli later)
  src/main/java/vn/vibefix/{module}/            public API of a module
  src/main/java/vn/vibefix/{module}/internal/   everything else
  src/main/resources/db/migration/{module}/     Flyway, V{yyyyMMddHHmm}__{module}_{description}.sql
frontend/   Vite, React 19, TypeScript, TanStack Query
  src/api/        generated from the backend's OpenAPI document; never edit by hand
  src/lib/        the only code that talks HTTP (apiClient)
  src/features/   one folder per backend module
  src/i18n/       vi.json, the only source of UI text
deploy/     Docker Compose stack (caddy, app, sandbox, egress, postgres), smoke test, ZAP rules
.github/    CI gates: build and tests, secret scan, ZAP baseline; image publication from main
```

## Requirements

- JDK 25 (the spine pins Eclipse Temurin 25.0.4.1, which CI and the images use)
- Docker, for Testcontainers PostgreSQL 18.6
- Node.js 24 (see `frontend/.nvmrc`)

Keep the checkout on a path without diacritics: the JVM cannot load classes from such paths.

## Build and test

From the repository root:

```sh
(cd backend && ./mvnw -B verify)    # unit tests, module checks, integration tests on PostgreSQL
(cd frontend && npm ci && npm run lint && npm run build)
```

`./mvnw verify` regenerates `frontend/src/api/openapi.json` from the running application, and
`OpenApiExportIT` fails when the committed file was stale: commit the regenerated file and run
again. `npm run build` (and `npm run dev`) regenerate `src/api/schema.d.ts` from it. Record
components are `required` in the document unless annotated `@Nullable`.

Integration tests extend `vn.vibefix.support.IntegrationTest`: the application connects as the
runtime role `vibefix_app`, Flyway as the migration role `vibefix_migrator`, the clock is a
`MutableClock`, and the database is recreated from a template before each test (never by deleting
rows).

## Running the stack

The whole platform runs locally as a Docker Compose stack shaped like production; see
[deploy/README.md](deploy/README.md):

```sh
bash deploy/dev-secrets.sh
docker compose -f deploy/compose.yaml up -d --build --wait
bash deploy/smoke.sh                 # then open http://localhost:8000
```

## Running the backend

The application needs exactly one of the profiles `dev` or `prod`, and refuses to start if the
`platform.environment` row of its database names the other one. `prod` also refuses the dev seed
location (`db/seed`) and the dev GitHub organization. It reads database passwords only from secret
files (one file per property name, `db-app-password` and `db-migrator-password`) in
`VIBEFIX_SECRETS_DIR` (default `/run/secrets/`), and refuses to start when one of them is set as an
environment variable or a JVM system property. The database host and port come from
`VIBEFIX_DB_HOST` and `VIBEFIX_DB_PORT` (default `localhost:5432`, database `vibefix`). The
application runs in UTC (`main` sets the default time zone; tests and `./mvnw spring-boot:run`
also pass `-Duser.timezone=UTC`). Logs are JSON lines (Elastic Common Schema) with a
`correlationId` per request, which responses return in `X-Correlation-Id`. Actuator exposes only
`/actuator/health` (with `liveness`, `readiness` and the `sandbox` round trip).

The profile `sandbox` starts the sandbox worker instead of the application: no database, and only
the job handlers (`POST /internal/sandbox/jobs`). The application reaches it at
`vibefix.sandbox.url` (default `http://sandbox:8080`).

To run the backend outside Docker, start a PostgreSQL 18 yourself with the roles from
`backend/src/test/resources/db/init-roles.sql` (each given a password) and a database `vibefix`
owned by `vibefix_migrator`, and put the two passwords in `deploy/secrets/` (ignored by git). No
sandbox worker runs outside Compose, so pass `--management.health.sandbox.enabled=false`, or
`/actuator/health` reports DOWN:

```sh
cd backend
VIBEFIX_SECRETS_DIR=../deploy/secrets/ ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments=--management.health.sandbox.enabled=false
```

## Conventions enforced by the build

- `ModularityTests`, `ModuleLayersTest`: module boundaries and the AD-3 layer table.
- `ClockRuleTest`: business time only from the injected `Clock` (calls and method references).
- `MigrationConventionsTest`: migration folder, name, schema ownership (names must be qualified
  with the module's schema), and immutability against `main` (set
  `-Dvibefix.migrations.base-ref=...` when `main` is not available locally).
- `EnvironmentGuardIT`, `SecretSourceGuardTest`: the environment guard (AD-25), the prod refusal of
  dev seed data and the dev organization (SG-36), and secrets from files only (AD-22).
- `SandboxRoundTripIT`: the sandbox worker's job channel and its isolation report (AD-32).
- `ApplicationBootIT`: security headers, correlation IDs, Actuator limited to health, the dev seed.
- `npm run lint`: no raw HTML (`dangerouslySetInnerHTML`, `innerHTML`, `insertAdjacentHTML`,
  `document.write`), no `fetch`, `XMLHttpRequest`, `EventSource`, `WebSocket`, `sendBeacon` or
  `openapi-fetch` outside `src/lib`, and a self-test that these bans still fire.
