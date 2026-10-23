# deploy

The local development stack of vibefix: four services, each published on localhost only.

| Service | Address | What it is |
|---|---|---|
| `frontend` | <http://localhost:5173> | Vite dev server on `frontend/` (Node 24), hot reload; `/api` is proxied to `backend` |
| `backend` | <http://localhost:8080> | Spring Boot, profile `dev`; Swagger UI at `/swagger-ui/index.html`, health at `/actuator/health`; uploads on the volume `files` |
| `postgres` | `localhost:5432` | PostgreSQL 18, database `vibefix`, volume `pgdata` |
| `mailpit` | <http://localhost:8025> | Catches every email the backend sends (SMTP on `localhost:1025`); nothing leaves the machine |

```text
compose.yaml          the four services
compose.integrations.yaml  the dev GitHub Apps and VNPay merchant for the backend (checkpoint 1, below)
.env.example          the IDs compose.integrations.yaml reads from deploy/.env (ignored by git)
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
- Logs: `docker compose -f deploy/compose.yaml logs -f backend` (or `frontend`, `postgres`, `mailpit`).

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

After pulling a change that adds a secret (Story 1.16 added `identity-person-signal-key-1`, the key
of the person-link signals; Story 2.3 added `scanning-fingerprint-key`, the key of the Finding
fingerprints), run `deploy/dev-secrets.sh` again: it creates only the missing files,
and the backend refuses to start without them.

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

## The first Administrator

Administrators sign in on `/quan-tri/dang-nhap` with an email, a password, and a code from an
authenticator app (FR-2, AD-19); no page links there. Create the first one with the bootstrap CLI,
which runs the backend image under the `cli` profile (no web server, no pollers) and refuses once
any Administrator exists:

```sh
docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli backend   --vibefix.cli.command=create-first-administrator --email=ten@vibefix.test --name="Tên của bạn"
```

It asks for the password twice (12 characters or more), then prints the TOTP secret and its
`otpauth://` URI once: add it to an authenticator app (Google Authenticator, Microsoft
Authenticator, 1Password) right away, because it is never shown again. Without a terminal, pass
`--password-file=<path inside the container>` or the `VIBEFIX_ADMIN_PASSWORD` variable instead.

Five wrong sign-ins within 15 minutes lock that email for 15 minutes and alert the other
Administrators. An Administrator session ends after 30 minutes without a command, or 8 hours after
sign-in; ADM-1's automatic refresh does not keep it open.

## The end-to-end path (checkpoint 1)

Checkpoint 1 runs the Mentor path with a one-Student Crew on a test repository: Mentor approval and
Team, intake, an Assessment Job, a Code Job with two Milestones, and closure with a Final Merge. Beyond
the stack above it needs the dev Platform App, the dev Merge App, and the dev VNPay sandbox merchant.

1. **Give the backend the three accounts.** Copy `.env.example` to `deploy/.env` and fill in the App
   IDs, the Platform App's installation ID on `vibefix-dev`, and the VNPay terminal code. The keys stay
   in `deploy/secrets/` as `github-platform-app-key.pem`, `github-merge-app-key.pem`, and
   `vnpay-hash-secret`. Then start the stack with both files:

   ```sh
   docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml up -d --build --wait
   ```

   Pass both files to every later `up` too; `up` with `compose.yaml` alone drops the accounts again.
2. **Point the Merge App back at localhost.** On the Merge App's settings page, set the Setup URL to
   `http://localhost:5173/api/v1/github/merge-app/setup` and tick "Redirect on update". GitHub sends
   the Customer there after the install, and vibefix binds the installation to the Job.
3. **Relay each VNPay IPN by hand.** VNPay cannot reach localhost, and the IPN is the only request that
   changes a Payment Order (AD-14). After paying, VNPay sends the browser to
   `http://localhost:5173/thanh-toan/<order>?vnp_…`; the return carries the same signed fields as the IPN.
   Within 15 minutes of creating the order, open the same query on the backend, which answers
   `{"RspCode":"00",…}`:

   ```text
   http://localhost:8080/webhooks/vnpay/ipn?vnp_Amount=…   (everything after the "?" of the return address)
   ```

4. **Wait for the sweeps instead of webhooks.** No GitHub webhook reaches localhost either. Accepted
   invitations, pushes to the Working Repository, and organization drift are read back every 10 minutes
   (`vibefix.repo.grant-sweep`, `vibefix.repo.push-sweep`).
5. **Use four accounts, one browser profile each.** A GitHub account has one role, and a browser holds
   one vibefix session at a time:
   - the Customer, who owns the public Source Repository and has admin rights on it;
   - the Mentor and the Student, two GitHub accounts that are not members of `vibefix-dev` (a member
     gets access without an invitation, which alerts Administrators), each with two-factor
     authentication on;
   - an Administrator, created with the bootstrap CLI ("The first Administrator", above).

   The Student verifies with a school email code: `ten@truong.test` is a seeded school domain, and the
   code arrives in Mailpit.
6. **Clean up after a run.** The Intermediary and Working Repositories are public repositories in
   `vibefix-dev`; delete them by hand.

## Spike S1: the admin check for organization-owned repositories

Story 1.14 asks whether a Customer's stored sign-in token sees `permissions.admin` on a repository
owned by an organization (AD-13, S1). Only the author can run it, on GitHub, with the dev OAuth App
registered ("Signing in with GitHub locally" above).

1. In `vibefix-dev`, create four public test repositories:
   - one owned by your own GitHub user (`<you>/s1-user`);
   - `vibefix-dev/s1-open`, with the organization's OAuth app access restrictions **off**;
   - `vibefix-dev/s1-restricted`, the same check after you turn restrictions **on** (Settings →
     Third-party access → OAuth application policy), without approving the dev OAuth App;
   - `vibefix-dev/s1-member-admin`, where a second GitHub account that is **not** an organization
     owner has the admin role on that repository only.
2. Sign in on http://localhost:5173 as a Customer with each account whose token you want to test,
   so its token is stored.
3. Run the probe for each Customer. It reads the repositories with that stored token and prints
   the owner type, the HTTP status, and `permissions.admin`; it never prints the token.

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli backend   --vibefix.cli.command=spike-s1 --github-login=<Customer> --repo=<you>/s1-user --repo=vibefix-dev/s1-open --repo=vibefix-dev/s1-member-admin
   ```

   Turn the restrictions on, then run it again with `--repo=vibefix-dev/s1-restricted`.
4. As the non-owner repository admin, try to install the dev Merge App on `s1-member-admin` only,
   and note whether GitHub allows it or asks an organization owner (SG-21). The Merge App is
   registered in Epic 2; if it does not exist yet, record that this check waits for it.
5. Record the results, and the decision they lead to, in the spine's memlog: accept
   organization-owned repositories through the AD-13 path, or refuse them with
   `ORG_REPO_UNSUPPORTED`. A result that contradicts AD-10 or AD-13 goes to `bmad-architecture`
   before Epic 2's registration story starts.

## Spike S4: the Platform App runs the repository mechanism

Story 2.1 asks whether the Platform App can do everything AD-10 and AD-11 rely on: copy a public
repository that has `.github/workflows/` into the organization, fork it several times there, push a
`handover/*` branch, invite a collaborator with read and raise them to write, read the
organization's settings, and protect the default branch with a ruleset (S4, SG-17, SG-21). Only the
author can run it, on GitHub, as an owner of `vibefix-dev`.

1. **Register the dev Platform App** (Settings → Developer settings → GitHub Apps, owned by
   `vibefix-dev`), with AD-10's permissions and nothing more:
   - repository: Administration, Contents, Pull requests, and Workflows **write**; Metadata **read**;
   - organization: Administration and Members **read**;
   - events: `push`, `pull_request`, `repository`, `member`, `membership`, `organization`,
     `repository_ruleset`, `meta`.

   Generate a private key and save it, unopened by anything else, as
   `deploy/secrets/github-platform-app-key.pem` (the folder is ignored by git). Install the App on
   `vibefix-dev` with **Only select repositories**, and note the App ID and the installation ID
   (the number at the end of the installation's settings URL).
2. **Pick a public source repository** that has at least one file in `.github/workflows/`, for
   example one of your own.
3. **Run the probe.** It mints one installation token per check, narrowed to that check's
   repository and permissions, revokes it after, and never prints it. `--collaborator` is a second
   GitHub account of yours that is a member of `vibefix-dev` but not an owner.

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/github-platform-app-key.pem:/run/secrets/github-platform-app-key:ro backend --vibefix.cli.command=spike-s4 --vibefix.github.platform-app.id=<App ID> --vibefix.github.platform-app.installation-id=<installation ID> --vibefix.github.platform-app.key-file=/run/secrets/github-platform-app-key --source=<owner/name> --collaborator=<second account> --forks=3
   ```

   Each check prints `OK`, `FAIL`, or `SKIPPED` with GitHub's status. The created repositories
   (`s4-…-intermediary` and its `-working-n` forks) stay for you to inspect; nothing is deleted.
4. **Check the ruleset against a member.** Accept the invitation as the second account, create a
   fine-grained personal access token for it with Contents write on the Intermediary Repository, save it
   as `deploy/secrets/s4-member-token`, and run the member checks. This token is the probe's input
   for this check only; the platform itself never uses a personal access token (AD-10).

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/github-platform-app-key.pem:/run/secrets/github-platform-app-key:ro -v ./deploy/secrets/s4-member-token:/run/secrets/s4-member-token:ro backend --vibefix.cli.command=spike-s4 --vibefix.github.platform-app.id=<App ID> --vibefix.github.platform-app.installation-id=<installation ID> --vibefix.github.platform-app.key-file=/run/secrets/github-platform-app-key --intermediary=<s4-…-intermediary> --member-token-file=/run/secrets/s4-member-token
   ```

   The member first pushes to `s4-member-control`, a branch no ruleset guards: it must read
   `accepted`, or the token cannot write there and the refusals that follow prove nothing. Every
   other attempt (direct push, force push, deletion, `handover/*` push) should read `refused`.

   GitHub shows a ruleset's bypass actors only to a token with Administration write, so check 6
   reads them with one and reports whether the read-only token saw them. Forks are requested with a
   token not narrowed to the Intermediary Repository, because GitHub forks for an App only when it
   has every repository at the destination. The JGit `/nonexistent/.config/jgit` errors in the log
   are harmless (the image's user has no home); `-e XDG_CONFIG_HOME=/tmp` silences them.
   A pending invitation keeps the permission it was sent with, so step 3's raise does not reach it;
   AD-11 raises only after acceptance. Accept the invitation as the collaborator
   (`https://github.com/vibefix-dev/<s4-…-intermediary>/invitations`), then raise them:

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -e XDG_CONFIG_HOME=/tmp -v ./deploy/secrets/github-platform-app-key.pem:/run/secrets/github-platform-app-key:ro backend --vibefix.cli.command=spike-s4 --vibefix.github.platform-app.id=<App ID> --vibefix.github.platform-app.installation-id=<installation ID> --vibefix.github.platform-app.key-file=/run/secrets/github-platform-app-key --intermediary=<s4-…-intermediary> --collaborator=<second account>
   ```

   Check 13 expects the raise to answer 204, no invitation left, and `role_name=write` read back with a
   read-only token.
5. **Repeat with "All repositories"**: change the installation's repository access, run step 3
   again with another `--prefix`, and note whether any result changed (SG-21).
6. **Check the registrations** (SG-20): register the dev Merge App too (Contents and Pull requests
   write, Metadata read, no events), then run the tagged suite. It fails if either App's permissions
   or events differ from AD-10's list. `GET /app` never lists the global `meta` event, even when the
   App subscribes to it, so check the Meta box on the Platform App's "Permissions & events" page by eye.

   ```sh
   VIBEFIX_DEV_PLATFORM_APP_ID=<id> VIBEFIX_DEV_PLATFORM_APP_KEY_FILE=../deploy/secrets/github-platform-app-key.pem VIBEFIX_DEV_MERGE_APP_ID=<id> VIBEFIX_DEV_MERGE_APP_KEY_FILE=../deploy/secrets/github-merge-app-key.pem ./mvnw -B test -Pvibefix-dev
   ```

7. **Record the outcome** of every check, and of the "All repositories" run, in the spine's memlog.
   An operation that fails goes to `bmad-architecture` before Epic 7, because AD-10 and AD-11
   depend on it; then tell Amelia, who records it and closes Story 2.1.

## Code Job setup on the dev stack (Story 7.2)

When Milestone 1 of a Code Job is paid, vibefix sets up its repositories under the Job's busy marker.
It checks the Source Repository again with the Customer's token and scans it again. Then it copies the
approved commit into `job-…-intermediary` in `vibefix-dev`, forks that into `job-…-working`, and reads
the organization settings and both rulesets back. JOB-H shows the three steps.

On the dev stack this needs the Platform App configured for the backend, as for Spike S4:
`vibefix.github.platform-app.id`, `installation-id`, and `key-file`, with the key mounted read-only from
`deploy/secrets/`; `compose.integrations.yaml` does this ("The end-to-end path", below). Without them
every attempt fails with `GITHUB_UNAVAILABLE`. After the retries, the
operation waits in ADM-7's failed queue (Thao tác lỗi), and an Administrator retries it there once the
App is configured.

- **A block** (a new Finding, a private repository, lost admin rights, or a missing sign-in) stops the
  start and tells the Customer and the Mentor why. The Customer fixes it and taps "Thử lại" on JOB-H.
- **The copies are public repositories** in the organization. Delete them by hand after a dev run.
- **Invitations follow (Story 7.3).** The Mentor and each active Crew member are invited to the Working
  Repository with read. Each accepts on GitHub, with two-factor authentication on. vibefix reads
  acceptance back every 10 minutes (`vibefix.repo.grant-sweep`), or at once on a `member` webhook.
- **Verify** runs once everyone is ready. It raises them to write, reads it back, and only then is the
  Job Active.
- **A `vibefix-dev` member** is added by GitHub without an invitation. Administrators are alerted;
  remove the person from the organization.
- **Pushes to the Working Repository are scanned (Story 7.4).** A `push` webhook, or the 10-minute
  sweep (`vibefix.repo.push-sweep`), reads the branch and tag heads back. Any change scans the
  repository again. A credential goes to the Mentor ("Đã đổi khóa" on the Job's page) and personal
  data to the Customer. While a Finding is open, the next handover waits.

## Spike S2: VNPay sandbox refunds and transaction queries

Story 4.5 asks what the dev VNPay sandbox merchant answers: whether full and partial refunds are
enabled, whether `querydr` answers, and the exact response codes of the return (which carries the
IPN's fields), `querydr`, and refunds, including what `04` and `94` mean in practice (S2, SG-24).
Only the author can run it, with the merchant VNPay gives them. vibefix only ever calls
`sandbox.vnpayment.vn` and refuses to start with any other VNPay URL (AD-14).

1. **Register the sandbox merchant** on VNPay's sandbox registration page. VNPay emails a terminal
   code (`vnp_TmnCode`) and a hash secret. Save the hash secret, and nothing else, as
   `deploy/secrets/vnpay-hash-secret` (the folder is ignored by git). The terminal code goes on the
   command line.
2. **Create a payment.** The amount is whole VND (at least 5.000 ₫).

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/vnpay-hash-secret:/run/secrets/vnpay-hash-secret:ro backend --vibefix.cli.command=spike-s2 --vibefix.payment.vnpay.tmn-code=<terminal code> --vibefix.payment.vnpay.hash-secret-file=/run/secrets/vnpay-hash-secret --vibefix.payment.vnpay.return-url=http://localhost:5173/thanh-toan/ket-qua --step=pay --amount=10000
   ```

   Open the printed URL, pay with the test card from VNPay's sandbox page, and keep `vnp_TxnRef` and
   `vnp_CreateDate`.
3. **Check the return.** VNPay sends the browser back to the return URL (the page itself does not
   exist yet). Copy the whole address and run the same command with
   `--step=return --query='<the address>'`. It prints `vnp_ResponseCode`, `vnp_TransactionStatus`,
   `vnp_TransactionNo`, and whether the hash verified. The IPN carries the same fields; it needs a
   public URL, so it waits for Story 4.6.
4. **Query it.** Run `--step=query --txn-ref=<vnp_TxnRef> --transaction-date=<vnp_CreateDate>`, once
   right away and once again within a few seconds, to see the duplicate-request answer.
5. **Refund it**, partly first, then the rest:
   `--step=refund --txn-ref=<ref> --transaction-date=<vnp_CreateDate> --transaction-no=<vnp_TransactionNo> --amount=3000 --type=03`,
   then `--amount=7000 --type=02`. Repeat one refund at once to see a duplicate. Make a second
   payment and refund it whole with `--type=02`.
6. **Record the outcome** in the spine's memlog: every step's `vnp_ResponseCode` and `vnp_Message`,
   whether each answer's hash verified, and what `04` and `94` meant. The decision is `VnpayRefund`
   if sandbox refunds work, otherwise `LedgerRecordedRefund` (simulated test-mode money, AD-15). Then
   tell Amelia, who records it and closes Story 4.5.

The probe prints no hash and never the secret. Exit code 0 means VNPay answered with a hash that
verified, whatever its codes; 1 means it did not answer or its hash did not verify.

## VNPay refunds

The author chose `VnpayRefund` after Spike S2 (2026-10-21, AD-15). A refund goes through VNPay's
`refund` call with the environment's sandbox merchant (`vibefix.payment.vnpay.tmn-code` and
`hash-secret-file`). VNPay refunds a payment once, so each refund carries the whole amount it
returns: type `02` for the full paid amount and `03` for less.

- **A signed `00`** confirms the refund at once. VNPay shows it as processing (`05`), and no later
  query confirms it.
- **A refusal goes to the failed queue (ADM-7).** This covers a signed refusal (`93` invalid amount,
  `94` already refunded, or any other code) and a missing merchant (`VNPAY_NOT_CONFIGURED`). Check the
  transaction on VNPay's merchant portal before retrying.
- **An unsigned answer** is no answer, and is retried with the same `vnp_RequestId`.

`vibefix.payment.refund-gateway=ledger` (`VIBEFIX_PAYMENT_REFUND_GATEWAY=ledger`) records refunds on
vibefix only, labelled "Hoàn tiền mô phỏng". The tests use it, and it is the fallback when VNPay
refunds are off.

## Email locally

The backend sends email as External Operations over SMTP (AD-6, AD-27). On this stack the SMTP
server is Mailpit: open <http://localhost:8025> to read every message, with its text and HTML parts.
The email lane polls every 30 seconds, so a message appears within about half a minute. While
Mailpit is down, emails wait and retry (30 s, doubling, 8 attempts). A backend run outside Compose
uses `localhost:1025`, the same Mailpit.

In production the server is the provider chosen at deployment (Resend or Brevo): set
`vibefix.email.smtp.host`, `port` and `vibefix.email.link-base-url`, and put the credentials in the
secret files `smtp-username` and `smtp-password`. The `prod` profile refuses to start without a host.
The daily budget is `vibefix.email.daily-budget` (100, Resend's free tier); past it, email waits
for the next UTC day, and the send that crosses 80% records the `notification.email_budget_80` alert.

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
