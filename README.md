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
deploy/     local Docker Compose stack (frontend, backend, postgres); later/ parks the production
            shape and the CI workflow until the dev environment works (no .github/ for now: no CI,
            no Dependabot; both return in Epic 18)
```

## Requirements

- JDK 25 (the spine pins Eclipse Temurin 25.0.4.1, which the backend image uses)
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

The platform runs locally as three Docker Compose services, each on a localhost port: the frontend
at <http://localhost:5173> (Vite, hot reload), the backend at <http://localhost:8080> (Swagger UI
at `/swagger-ui/index.html`) and PostgreSQL at `localhost:5432`. See
[deploy/README.md](deploy/README.md) for the database users and the parked production shape:

```sh
bash deploy/dev-secrets.sh
docker compose -f deploy/compose.yaml up -d --build --wait
```

## Branches and merging

No CI runs for now, so nothing checks a branch before it reaches `main`. Work on a branch, never
on `main` itself, and name it `<type>/<short-description>`, in lowercase words joined by hyphens:

| Type | For | Example |
|---|---|---|
| `feature/` | a story or another new capability; stories start with their number | `feature/1-3-audit-and-authorization` |
| `fix/` | a bug fix | `fix/environment-guard-message` |
| `chore/` | build, Docker, dependencies, tooling | `chore/simplify-local-stack` |
| `docs/` | documentation only | `docs/deploy-readme` |
| `refactor/` | a code change that keeps behaviour | `refactor/sandbox-job-client` |
| `test/` | tests only | `test/migration-conventions` |

Commit messages follow Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`,
`test:`, `build:`), as the history already does. Before merging, run the build and tests above
(what CI used to run), then merge with a merge commit, push, and delete the branch:

```sh
git switch main
git pull
git merge --no-ff feature/1-3-audit-and-authorization
git push origin main
git branch -d feature/1-3-audit-and-authorization
```

A pull request on GitHub, merged there, works as well.

No secret scan runs until CI is back (Epic 18). To check the history yourself before a merge, run
gitleaks from the repository root (Git Bash; exit code 0 means nothing was found):

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/repo:ro" \
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
  ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  git /repo --redact
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
- `AuditLogIT`, `AuditAppendOnlySourceTest`: the Audit Log is written in the caller's transaction
  and never changed: triggers, insert-and-select grants, and no purge in the sources (AD-8).
- `AuthorizationMatrixIT`, `MatrixCoverageIT`, `AuthorizationHarnessIT`: every `/api` and
  `/webhooks` handler declares its `@Access` gate and has rows in
  `src/test/resources/authz/matrix.csv`; every row runs, and no canary of `authz/canaries.csv`
  reaches a caller it is hidden from (AD-20). Add callers and seeded objects as fixtures in
  `vn.vibefix.authz.fixtures`.
- `PersonalDataCoverageIT`: every table that can hold personal data has a
  `PersonalDataParticipant` (AD-24). `AdminReadGrantCallersTest`: only `dispute` and `admin` push
  Administrator read grants down (AD-20).
- `EventPublicationIT`, `EventRepublishOnRestartIT`: event publications are republished at start,
  resubmitted after 5 minutes, stalled after 5 attempts, replay-safe, and purged after 30 days (AD-9).
- `npm run lint`: no raw HTML (`dangerouslySetInnerHTML`, `innerHTML`, `insertAdjacentHTML`,
  `document.write`), no `fetch`, `XMLHttpRequest`, `EventSource`, `WebSocket`, `sendBeacon` or
  `openapi-fetch` outside `src/lib`, and a self-test that these bans still fire. Then the
  plain-term check: Customer-facing text in `vi.json` uses no technical word that the Plain-Term
  List (`term.*`, audiences in `term-audience.json`) does not list (NFR-12).
- `npm run build`: the type check, the Vitest suite (`npm test`), the Vite build, and the
  static-style check: no bundled code creates a `<style>` element or a style sheet at run time,
  which the CSP would block (UX-DR2). Base UI's Select and ScrollArea do, so they are not used.
