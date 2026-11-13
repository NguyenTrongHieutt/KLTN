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
.env.example          the IDs compose.integrations.yaml and compose.yaml read from deploy/.env (ignored by git)
postgres/initdb/      creates the roles and the database on an empty volume
dev-secrets.sh        creates the local secret files in deploy/secrets/ (ignored by git)
customer/             Spike S6: vibefix.yml, the workflow a Customer adds to the Source Repository (below)
runner/               Spike S6: s6-copy.yml, which the probe writes to vibefix-dev/vibefix-runner (below)
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
of the person-link signals; Story 19.1 added `google-oauth-client-id` and `google-oauth-client-secret`), run
`deploy/dev-secrets.sh` again: it creates only the missing files, and the stack needs every
mounted file to exist, even an empty one.

## Signing in with Google locally

Since Story 19.1 users sign in with Google only (FR-1, AD-19, AD-25), through a Google OAuth client
of the dev environment. Until it exists, the two files `secrets/google-oauth-client-id` and
`secrets/google-oauth-client-secret` stay empty, and the "Đăng nhập bằng Google" button on
http://localhost:5173/dang-nhap answers that sign-in is not set up. Production refuses to start
without them.

1. In the Google Cloud console, in a project of the dev environment, open APIs & Services ›
   OAuth consent screen (Google Auth Platform):
   - User type: External, publishing status **Testing**, so only the listed test users can sign in.
   - Scopes: none beyond the defaults; vibefix asks for `openid`, `email`, and `profile` only.
   - Test users: the Gmail accounts that will sign in, for checkpoint 1 its three accounts.
2. Under Credentials (Clients), choose Create credentials › OAuth client ID:
   - Application type: Web application.
   - Authorized JavaScript origins: none needed.
   - Authorized redirect URI: `http://localhost:5173/login/oauth2/code/google` (Vite forwards
     `/login/oauth2` to the backend, so the callback is the SPA's origin).
3. Copy the Client ID into `secrets/google-oauth-client-id` and the Client secret into
   `secrets/google-oauth-client-secret`. Write each value without a trailing newline (the files are
   read-only: `chmod 644` them first, and `chmod 444` after).
4. Restart the backend: `docker compose -f deploy/compose.yaml up -d --force-recreate backend`.
   On a checkpoint 1 stack, pass `-f deploy/compose.integrations.yaml` too, or the backend loses the
   Apps and VNPay.

The ID token is checked by Spring against Google's keys, issuer, audience, and nonce, and the
account is keyed by Google's subject (`sub`), never the email. vibefix keeps no Google token and no
picture; it keeps the Google subject, the Google name, and the verified Google email address, where
notifications are emailed.
An address Google has not verified is refused with "email chưa xác minh". The first sign-in opens
PUB-3 "Chào mừng"; accepting N-10 there opens the `identity.link_github` to-do. To try a locked
account, set it in the database:
`UPDATE identity.account SET locked_at = now(), lock_reason = '...' WHERE google_email = '...'`.

Accounts created before Story 19.1 carry a GitHub ID and no Google subject, so they can no longer
sign in. Resetting the dev database removes them (an author action, not done by any story):
`docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml down -v`.

## Linking GitHub locally

GitHub signs nobody in (Story 19.1): `/oauth2/authorization/github` returns to PUB-2. Since Story
19.2 a signed-in account links its GitHub account through this OAuth App, from ACC-2
(`/ho-so/xac-minh`) or from the GitHub gate on CUS-3 step 2. Its two files
`secrets/github-oauth-client-id` and `secrets/github-oauth-client-secret` stay mounted, and
production still refuses to start without them. Without them, ACC-2 says linking is not set up.

1. On GitHub, under the `vibefix-dev` organization's Settings › Developer settings › OAuth Apps (or
   your own account's, while that organization does not exist yet), choose New OAuth App, or open
   the existing dev app:
   - Homepage URL: `http://localhost:5173`
   - Authorization callback URL: `http://localhost:5173/link/oauth2/code/github` (Vite forwards
     `/link/oauth2` to the backend). **Author action for Story 19.2:** an app made before it has
     `http://localhost:5173/login/oauth2/code/github` here; change it to the new address. An OAuth
     App has one callback URL, and GitHub refuses a `redirect_uri` that is not that address or a
     path under it.
2. Copy the Client ID into `secrets/github-oauth-client-id`, then generate a client secret and copy
   it into `secrets/github-oauth-client-secret`. Write each value without a trailing newline (the
   files are read-only: `chmod 644` them first, and `chmod 444` after).
3. Restart the backend: `docker compose -f deploy/compose.yaml up -d --force-recreate backend`.
   On a checkpoint 1 stack, pass `-f deploy/compose.integrations.yaml` too, or the backend loses the
   Apps and VNPay.

The app asks only for `read:user` and `user:email` (FR-80), with PKCE and a `state` bound to the
session. Since Story 23.5d (R31) the link keeps identity only, as Google's sign-in does: as soon as
GitHub issues a token, the callback asks for the whole grant to be revoked on GitHub
(`DELETE /applications/{client_id}/grant`, through the `identity.revoke_oauth_grant` operation, which
carries the token sealed with `secrets/identity-token-key-1` until it runs), reads the person with
it once, and stores it nowhere. So a link made since this story leaves vibefix authorized on nobody's
GitHub account, and linking again shows GitHub's consent screen again. Revocations queued before this
story for one token alone were changed by its migration to revoke the whole grant. Repositories are
read through the Source App, never with this token. No token is held any more, so an unlink or an
account's deletion has nothing to revoke; a person who linked before this story can remove vibefix on
GitHub under Settings › Applications.

**Author action for Story 23.5d:** the dev OAuth App's authorizations granted before this story (the
tokens vibefix used to keep, which its migration deleted without revoking them) stay listed on your
GitHub account, and on any test account that linked, until you revoke them there: Settings ›
Applications › Authorized OAuth Apps, then Revoke on the dev app. Nothing in vibefix holds them.

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

Checkpoint 1 runs the context-roles path on a test repository with three users and a one-member Crew:
Google sign-in and GitHub linking, intake, an Assessment Job, a Code Job with two Milestones, each
Milestone's split (confirmed by the Crew member, or decided by an Administrator), and closure with a
Final Merge. Roles belong to each Job, never to an account. Beyond the stack above it needs the dev
Google client ("Signing in with Google locally"), the dev GitHub OAuth App with its linking callback
("Linking GitHub locally"), the dev Platform App, the dev Merge App, the dev Source App ("The Source App on
the dev stack", below), and the dev VNPay sandbox merchant. Until Story 23.6 moves the J7 copy onto the
Customer's Actions, the Source Repository of a Code Job must be public: Setup still reads its head with the
Platform App and clones it without a token.

A database from before Epics 19–22 cannot run this path: its accounts have no Google subject, and Story
22.4's ledger check stops the backend at boot while `RELEASE_HELD` rows remain. Reset it first (an
author action): `docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml down -v`.

1. **Give the backend the four accounts.** Copy `.env.example` to `deploy/.env` and fill in the App
   IDs (the Source App's too, `VIBEFIX_SOURCE_APP_ID`), the Platform App's installation ID on `vibefix-dev`,
   and the VNPay terminal code. The keys stay in `deploy/secrets/` as `github-platform-app-key.pem`,
   `github-merge-app-key.pem`, `github-source-app-key.pem`, and `vnpay-hash-secret`. Then start the stack with
   both files:

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
   invitations and organization drift are read back every 10 minutes (`vibefix.repo.grant-sweep`,
   `vibefix.repo.platform-sweep`). Pushes to the Working Repository need no webhook: vibefix reads the
   Working head live when the Mentor hands over, and compares heads only at acceptance (Story 23.2).
5. **Use four accounts, one browser profile each.** A browser holds one vibefix session, and one
   signed-in GitHub account, at a time:
   - three users, each with their own Google account (a test user of the dev Google client) and their
     own linked GitHub account: the Customer, who owns the public Source Repository and has admin
     rights on it; the Mentor and the Crew member, two GitHub accounts that are not members of
     `vibefix-dev` (a member gets access without an invitation, which alerts Administrators), each
     with two-factor authentication on and never a contributor to the Source Repository;
   - an Administrator, created with the bootstrap CLI ("The first Administrator", above).

   No two accounts may share an email address, whether a Google address, a verified GitHub email, a
   school email, or the Administrator's: a shared address is a person-link signal, so bids, Crew
   Applications and picks refuse with `CONFLICT_OF_INTEREST`, and an Administrator who shares one cannot
   take the case. Student verification is optional and gates nothing (`ten@truong.test` is a seeded
   school domain, and the code arrives in Mailpit). Verified on LinkedIn waits on Spike S5 and is not
   needed.
6. **Clean up after a run.** The Intermediary and Working Repositories are public repositories in
   `vibefix-dev`; delete them by hand. After the Final Merge, GitHub lists the Crew member (and the
   Mentor, if they committed) among the Source Repository's contributors, so the next run needs another
   test repository.

## Spike S1: the admin check for organization-owned repositories

**Historical:** S1 closed on 2026-10-19 (organization-owned repositories are accepted through
AD-13's path), and since Story 23.5 the admin check reads through the Source App. The probe is kept
only to repeat the experiment.

Story 1.14 asks whether a Customer's GitHub token sees `permissions.admin` on a repository owned by
an organization (AD-13, S1). Only the author can run it, on GitHub, with the dev OAuth App
registered ("Linking GitHub locally" above). Since Story 23.5d vibefix keeps no GitHub token, so the
probe never reads one from storage: it reads with the token in the process environment variable
`VIBEFIX_SPIKE_S1_TOKEN` only (never a `--` argument), and never prints it. That token must be a
user token of the dev OAuth App, obtained by hand through that app's web flow: authorize the app in
the browser, then exchange the code at `https://github.com/login/oauth/access_token` with the app's
client ID and secret. vibefix's own linking cannot give you one any more, since it revokes the grant
at once, and another app's token (such as the GitHub CLI's) would not test this app's access.

1. In `vibefix-dev`, create four public test repositories:
   - one owned by your own GitHub user (`<you>/s1-user`);
   - `vibefix-dev/s1-open`, with the organization's OAuth app access restrictions **off**;
   - `vibefix-dev/s1-restricted`, the same check after you turn restrictions **on** (Settings →
     Third-party access → OAuth application policy), without approving the dev OAuth App;
   - `vibefix-dev/s1-member-admin`, where a second GitHub account that is **not** an organization
     owner has the admin role on that repository only.
2. Get a dev OAuth App user token by hand, as above, for each account you want to test.
3. Run the probe for each account. Set the token without echoing it or leaving it in your shell
   history, then pass the variable by name. The probe reads the repositories with that token and
   prints the owner type, the HTTP status, and `permissions.admin`; it never prints the token.

   ```sh
   read -rs VIBEFIX_SPIKE_S1_TOKEN && export VIBEFIX_SPIKE_S1_TOKEN
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -e VIBEFIX_SPIKE_S1_TOKEN backend   --vibefix.cli.command=spike-s1 --repo=<you>/s1-user --repo=vibefix-dev/s1-open --repo=vibefix-dev/s1-member-admin
   unset VIBEFIX_SPIKE_S1_TOKEN
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
   `vibefix-dev`), with AD-10's permissions and nothing more (as of 2026-11-12, Story 23.3):
   - repository: Actions, Administration, Contents, Pull requests, and Workflows **write**; Metadata **read**;
   - organization: Administration and Members **read**;
   - events: `pull_request`, `repository`, `member`, `membership`, `organization`, `meta`. S4 ran with
     `push` and `repository_ruleset` too; the private copies dropped both (AD-12), and the daily
     configuration check now reports either one as a difference.

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
It checks the Source Repository again through the Customer's Source App (Story 23.5: the binding, the
admin right, and the published `vibefix.yml`) and reads its default branch's head through the GitHub API
(vibefix scans no code, Story 23.1). Before it creates anything, it reads the
organization, the runner, and the Platform App's installation back (Story 23.3). Then it creates
`job-…-intermediary` and `job-…-working` in `vibefix-dev` as two **private** repositories (Wiki, Issues,
Projects, Discussions, and forking off), reads each one's settings back, including Actions disabled by the
organization's policy, and only then pushes that commit into each, keeping its ID. There is no fork and no
ruleset: GitHub Free takes none on a private repository, so the sweep reads the Intermediary's
collaborators and invitations instead. JOB-H shows the three steps.

**The runner is required.** Set `VIBEFIX_GITHUB_RUNNER_REPOSITORY=vibefix-runner` in `deploy/.env` and keep
`vibefix-dev/vibefix-runner` as Spike S6 left it: private, holding only `.github/workflows/s6-copy.yml` on
`main`, with no collaborator or invitation, and the only repository the organization's Actions policy
("Selected repositories") lists. Since Story 23.4 that file must also be an accepted blob
(`vibefix.github.exchange.accepted-blobs`): copy `deploy/runner/s6-copy.yml` there unchanged whenever it
changes, as Spike S6's part b does. Unset or drifted, every new Setup waits 15 minutes and looks again, and
the 10-minute sweep alerts Administrators with `github.platform_drift`.

On the dev stack this needs the Platform App configured for the backend, as for Spike S4:
`vibefix.github.platform-app.id`, `installation-id`, and `key-file`, with the key mounted read-only from
`deploy/secrets/`; `compose.integrations.yaml` does this ("The end-to-end path", below). Without them
every attempt fails with `GITHUB_UNAVAILABLE`. After the retries, the
operation waits in ADM-7's failed queue (Thao tác lỗi), and an Administrator retries it there once the
App is configured.

- **A block** (the Source App uninstalled or on the wrong repositories, `vibefix.yml` removed or changed, or
  lost admin rights) stops the start and tells the Customer and the Mentor why. The Customer fixes it and
  taps "Thử lại" on JOB-H. Until Story 23.6 moves the copy onto the Customer's Actions, the sandbox still
  clones the Source Repository without a token, so a dev run needs a public test repository.
- **The copies are private repositories** in the organization. Every pull-request call holds Contents read,
  and the handover pin, the Final Merge trial and push, and the ZIP clone them with a token narrowed to the
  one repository with Contents read (Stories 23.8 to 23.10 replace these jobs). Delete them by hand after a
  dev run.
- **Invitations follow (Story 7.3).** The Mentor and each active Crew member are invited to the Working
  Repository with read. Each accepts on GitHub, with two-factor authentication on. vibefix reads
  acceptance back every 10 minutes (`vibefix.repo.grant-sweep`), or at once on a `member` webhook.
- **Verify** runs once everyone is ready. It raises them to write, reads it back, and only then is the
  Job Active.
- **A `vibefix-dev` member** is added by GitHub without an invitation. Administrators are alerted;
  remove the person from the organization.
- **Pushes to the Working Repository are neither scanned nor tracked (Stories 23.1, 23.2).** The team keeps
  working while the Customer reviews a handover. Only the acceptance merge compares, through GitHub's
  `sha` on `handover/{id}`: a 409 there voids the handover (M3) and alerts Administrators, and M3 or a
  revision (M4) closes the older pull request with a comment and "(lần n)" in its title, keeping its
  branch. vibefix scans no code: the Mentor keeps the team's secrets out.

## Actions exchange on the dev stack (Story 23.4)

GitHub Actions runs get their token from the backend's exchange, `POST /github/actions/exchange` (AD-44).
Before each dispatch the backend checks the target's workflow file on its default branch against
`vibefix.github.exchange.accepted-blobs` and records a correlation (a random UUID, in the run's title). The
run trades its OIDC ID token once for a Platform App token narrowed to the correlation's repositories, and a
30-second poller revokes it when the run completes, or 15 minutes after issue. A refusal after the ID token is
verified is audited and alerts Administrators (`github.exchange_refused`). Tokens live only in memory: a
backend restarted while one is out marks it `REVOKE_UNKNOWN` and alerts once, and GitHub expires it within
the hour.

GitHub's runners reach the dev stack through a cloudflared quick tunnel, which ends TLS at Cloudflare, so
the short-lived tokens pass through it. The tunnel points at port 8098, where the backend serves the
exchange and answers 404 to anything else, never at 8080.

1. **First, replace the runner's workflow.** Story 23.4 changed both published workflows (they post to
   `/github/actions/exchange`), so their blobs changed. On `vibefix-dev/vibefix-runner`, replace
   `.github/workflows/s6-copy.yml` on `main` with `deploy/runner/s6-copy.yml`, unchanged (in PowerShell,
   `Get-Content deploy/runner/s6-copy.yml -Raw | Set-Clipboard`, then edit the file on GitHub, Ctrl+A, Ctrl+V,
   commit to `main`; Spike S6's part b also writes it). Until then the 10-minute sweep alerts
   `github.platform_drift` for the runner's `s6-copy.yml` blob, new Setups wait, and `runner-dispatch` is
   refused with `WORKFLOW_MISMATCH`: both are expected. The Customer's copy of `vibefix.yml` in `s6-source` is
   stale the same way (it posts to the spike's old `/exchange`), so replace it there too before a Customer's
   run uses this exchange.
2. Start the tunnel in its own window, and keep it running:
   ```
   cloudflared tunnel --no-autoupdate --url http://localhost:8098
   ```
   It prints `https://<words>.trycloudflare.com`, a new one each start.
3. Put it in `deploy/.env` as `VIBEFIX_GITHUB_EXCHANGE_PUBLIC_URL=https://<words>.trycloudflare.com` (no path),
   and recreate the backend so it reads it (PowerShell, in `D:\KLTN\vibefix`):
   ```
   docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml up -d --build --wait backend
   ```
   Only the `dev` profile uses it; `prod` refuses to start with it set.
4. Dispatch a handover copy on the runner with `runner-dispatch`, naming two repositories of `vibefix-dev`
   without their owner, for example a Job's Working and Intermediary Repositories:
   ```
   docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli backend --spring.flyway.enabled=false --vibefix.cli.command=runner-dispatch --source=<working> --source-branch=main --sha=<commit on it> --target=<intermediary> --target-branch=handover/<name>
   ```
   It records the correlation, dispatches `s6-copy.yml`, prints the correlation and the run, and exits. The
   running backend exchanges the run's ID token and revokes the token once the run completes; the Audit
   Log shows `github.exchange_issued` and `github.exchange_revoked`. Exit code 1 means refused (not `dev`, no
   runner, a repository not found, the runner's `s6-copy.yml` not accepted, or GitHub's refusal), 2 a wrong
   invocation.

Spike S6's probe still serves its own exchange on 8099 for its parts b and c: it answers the published
workflows' new path, `/github/actions/exchange`, too.

## The Source App on the dev stack (Story 23.5)

Every read of a Source Repository goes through the Source App, the third GitHub App (AD-10): registration's
checks (CUS-3), and the Source checks before a Code Job's Setup and before the Final Merge. Each Customer
installs it on exactly their Source Repository, public or private, and adds the published
`deploy/customer/vibefix.yml` as `.github/workflows/vibefix.yml` on the default branch. vibefix binds the pair
(installation, repository) in `product.source_app_binding` when "Kiểm tra" finds the installation through
`GET /repos/{owner}/{repo}/installation`, mints every token from that installation narrowed to the one
repository, and revokes it after use.

1. **Settings of the dev Source App** (the App Spike S6 left, "Spike S6" below, step 4). On its settings page:
   - Repository permissions **Contents: Read**, **Metadata: Read**, **Actions: Read and write**, and nothing
     else; no organization or account permission; no event.
   - **Webhook: off** (untick "Active"). The daily configuration check compares only the registration for
     this App, and the redelivery job skips it.
   - **Setup URL** `http://localhost:5173/api/v1/github/source-app/setup`, with **"Redirect on update"**
     ticked, so GitHub sends the Customer back to CUS-3 after an install, a change of the selection, or an
     organization's request.
   - "Where can this GitHub App be installed?": **Any account**.
2. **Give the backend its ID and key.** Put the App ID in `deploy/.env` as `VIBEFIX_SOURCE_APP_ID`; its key is
   `deploy/secrets/github-source-app-key.pem` (from Spike S6). `compose.integrations.yaml` passes both, with no
   installation ID: each Customer has their own. Recreate the backend:
   ```
   docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml up -d --build --wait backend
   ```
   Without them, registration and the Source checks answer `GITHUB_UNAVAILABLE`.
3. **Run it once.** As a Customer on CUS-3, paste a test repository you administer, tap "Cài ứng dụng vibefix
   trên GitHub", choose "Only select repositories" and only that repository, and come back: the wizard runs
   "Kiểm tra" and binds it. On step 3, "Thêm file workflow" opens GitHub's new-file page with
   `.github/workflows/vibefix.yml` prefilled; commit it on the default branch, come back, and tap "Kiểm tra":
   "Đúng bản vibefix công bố". Then register. Checks to try on the way: select all repositories
   (`SOURCE_APP_WRONG_REPOSITORY`), uninstall (`SOURCE_APP_MISSING`), or edit the file
   (`WORKFLOW_MISMATCH`). An organization repository whose owner must approve the install comes back with
   "Chờ chủ tổ chức duyệt"; the next "Kiểm tra" binds it once approved.
4. **The published file changed in this story.** Story 23.5 split `vibefix.yml` into the jobs `copy`,
   `final-merge` and `scan` (each with only its permissions, and `copy` and `final-merge` refuse a run no GitHub
   App started), so its blob changed and the old one is no longer accepted. Replace the copy in `s6-source`
   (and any other test repository) with the new file before a Customer's run uses the exchange.
5. **Deleting a Product** ends its binding. vibefix never uninstalls the Customer's App: CUS-4 and CUS-2 tell
   them to deselect the repository on GitHub, or to uninstall the App when no other Product uses it.

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

## Spike S5: Verified on LinkedIn access and retention

Story 19.5 asks what Verified on LinkedIn gives the dev LinkedIn app before Story 19.6 builds on it
(S5, AD-42, AD-22). It checks whether the Development tier is granted, and what `/identityMe` and
`/verificationReport` return. LinkedIn's terms then say how long the facts may be kept and how they
are refreshed, whether `profileUrl` is stable, and which branding is required. Only the author can run
it, with their own LinkedIn account. The probe makes one consent with `r_profile_basicinfo` and
`r_verify`. It keeps the token in memory only, and neither reads nor writes the database. It creates
no account, no verification, and no signal.

1. **Create the dev LinkedIn app** on the LinkedIn developer portal (<https://www.linkedin.com/developers/apps>,
   which asks for a LinkedIn Page to own the app):
   - Products: request **Verified on LinkedIn** at the **Development** tier. The Lite tier, and its
     review, are for the production app (AD-25).
   - Auth › Authorized redirect URLs: `http://localhost:5173/spike-s5/linkedin`
     (`vibefix.linkedin.redirect-uri`). The Vite proxy does not forward this path, so the code goes no
     further than the SPA's not-found page, and you copy it from the address bar. Story 19.6's own
     callback will live under `/link/oauth2`, which Vite forwards to the backend.
   - Auth › OAuth 2.0 scopes should then list `r_profile_basicinfo` and `r_verify`.
2. **Save the two secret files.** Copy the Client ID into `deploy/secrets/linkedin-client-id` and the
   Primary Client Secret into `deploy/secrets/linkedin-client-secret`, then `chmod 444` both (to edit
   one later, `chmod 644` it first, and `chmod 444` after). They are secret files like the others: the
   application refuses to start with `LINKEDIN_CLIENT_SECRET` as an environment variable, a JVM system
   property, or a command-line argument. `compose.yaml` does not mount them; the two commands below do,
   and `dev-secrets.sh` leaves them alone. Create both files before the first run. For a missing file,
   Docker creates an empty folder in its place, and the probe answers that the secret file is missing.
   Delete such a folder before writing the file:
   `rm -rf deploy/secrets/linkedin-client-id deploy/secrets/linkedin-client-secret`.
3. **Authorize.** The probe starts the backend image under `cli` like the other probes, so the stack's
   `postgres` must be up and its database already migrated by the stack. Rebuild the image first if the
   branch changed (`docker compose -f deploy/compose.yaml build backend`). Both commands pass
   `--spring.flyway.enabled=false`, so the probe applies no migration to your dev database. Then run:

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/linkedin-client-id:/run/secrets/linkedin-client-id:ro -v ./deploy/secrets/linkedin-client-secret:/run/secrets/linkedin-client-secret:ro backend --spring.flyway.enabled=false --vibefix.cli.command=spike-s5 --step=authorize
   ```

   It prints a LinkedIn authorization URL with a new `state`, and that `state`. Open the URL and sign
   in to LinkedIn as yourself. Allow the consent, and complete LinkedIn's identity verification if it
   asks. LinkedIn then sends the browser to `http://localhost:5173/spike-s5/linkedin?code=…&state=…`,
   where the SPA says the page was not found. Copy that whole address at once: the code is short-lived
   and works once.
4. **Exchange.** Run the same command with the second step, quoting the address:

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/linkedin-client-id:/run/secrets/linkedin-client-id:ro -v ./deploy/secrets/linkedin-client-secret:/run/secrets/linkedin-client-secret:ro backend --spring.flyway.enabled=false --vibefix.cli.command=spike-s5 --step=exchange --state=<the state step 3 printed> --query='<the address>'
   ```

   It checks the `state`, then exchanges the code with the client secret. In the same process it reads
   `identityMe` and `verificationReport` with the token, under the `LinkedIn-Version` header
   (`vibefix.linkedin.api-version`, `202609` by default). LinkedIn serves each monthly version for
   about a year. For a version it no longer serves, the reads answer `HTTP 426, refused with code=…`
   (for example `NONEXISTENT_VERSION`) and a `message=` line. The code is used up by then, so start
   again from step 3, and add a recent version to the exchange command, for example
   `--vibefix.linkedin.api-version=202610`. For each call it prints the HTTP status and the JSON shape
   of the answer:
   - **Strings are masked.** Letters become `x` or `X` and digits `9`. A URL shows the form of its
     scheme, host and path, and the names of its query parameters only. A key that is not a plain field
     name (a member ID, a URN) shows as `key(N)`.
   - **Personal fields stay masked.** Below a field that names a person (a name, a URL, an ID, a
     picture), every value stays masked, numbers included.
   - **Some values show as they are:** upper-case values such as `IDENTITY` in `status`, `code`,
     `error`, `type`, the category fields, and inside `verifications`, a lower-case `scope`, and
     `downloadUrlExpiresAt`.
   - **Tokens show as `<redacted>`,** and so does any value that holds the code, the client secret, or
     a token.
   - **It also prints** the token's `expires_in` and `refresh_token_expires_in`, and each picture's
     `downloadUrlExpiresAt` with its date and how far away it is.

   A refusal prints LinkedIn's status and error code (`error=invalid_grant`, `code=ACCESS_DENIED`).
   From HTTP 400 up, it also prints LinkedIn's `message` and `error_description`. A `state` that does
   not match makes no token request. The token, the code, and the client secret are never printed,
   logged, or written.
5. **Record the Outcome** in Story 19.5's spec, section "Outcome". Paste the printed shapes (already
   masked): the fields, the member `id`, the form of `profileUrl`, the picture URL with
   `downloadUrlExpiresAt`, and the verification categories. From LinkedIn's documentation and the
   developer portal, answer these questions:
   - Does the Lite tier need a review, and what is its status?
   - How long may the facts be kept without a refresh? This sets `valid_until` of `LINKEDIN`
     verifications (90 days until then, AD-42).
   - How must the facts be refreshed: by verifying again, or with a refresh token?
   - Is `profileUrl` stable enough to show and to record as the `LINKEDIN` signal? If not, only
     `LINKEDIN_SUB` is recorded.
   - Which branding is required: the wording, a logo or an attribution, and whether it may be combined
     with other verification marks?
   - May the badge show from stored facts, in Vietnamese, next to the other verifications?
   - May the categories appear in the bid comparison? If not, LinkedIn shows on the public profile
     only.

   Then decide with Winston (architect) and Sally (UX designer). Their owners then update the spine's
   S5 row, AD-42's validity, N-25, and UX-DR92. Story 19.6 stays blocked in sprint status until then.
   If LinkedIn refuses access, or its terms forbid keeping the facts, Story 19.6 ships its Tier 3
   fallback ("Chưa xác minh", with no action).

Exit code 0 means LinkedIn answered every call. 1 means a refusal, a `state` that does not match, no
answer within 20 seconds, or a value no HTTP header allows. 2 means a wrong invocation, or a missing
setting or secret file (with the usage). LinkedIn lists the app among your permitted services, where
you can remove its access after the spike.

## Spike S6: copies on the Customer's Actions, one runner, tokens through OIDC

Spike S6 tries the Free-plan design before the correct-course rewrites AD-10, AD-11, and FR-18 to FR-21.
Since vibefix stops scanning, the correct-course also rewrites FR-8, FR-65, AD-18, AD-32, Epic 3, and
Story 7.4. This is the revision of 2026-11-10. It replaces the first probe of 2026-11-09, which used a
runner organization, a Runner App, sealed tokens, and a scan in Actions. The first probe's question of
what a scan costs in Actions minutes is dropped with the scan. In this design:

- vibefix scans nothing;
- the Intermediary and Working Repositories are private copies, and the Customer reads only the
  Intermediary Repository;
- the J7 copy (the Customer's repository to the Intermediary and Working Repositories) and the Final
  Merge (the Intermediary Repository to a new `vibefix/…` branch of the Customer's repository) run on
  GitHub Actions in the Customer's own repository. They come from one file the Customer adds:
  `deploy/customer/vibefix.yml`, as `.github/workflows/vibefix.yml`. The Source App dispatches it;
- the handover copy (the Working Repository to `handover/…` on the Intermediary Repository) runs in
  `vibefix-dev/vibefix-runner`, the only repository of `vibefix-dev` whose Actions may run
  ("Selected repositories"). The Platform App dispatches `deploy/runner/s6-copy.yml` there. There is no
  Runner App and no second organization;
- no workflow holds a secret. Each run asks GitHub Actions for an OIDC ID token with the audience
  `vibefix`, and exchanges it at vibefix for a Platform App token narrowed to its repositories. During
  the spike the probe serves that exchange itself, reached through a cloudflared quick tunnel;
- the same `vibefix.yml` offers the Customer an optional `scan` operation, to help with the J7
  self-confirmation that secrets and personal data are removed or revoked. The Source App may dispatch
  it, and the Customer may start it from the repository's Actions tab, with every other input empty
  (the correlation too). It runs in a job of its own, `scan`, whose permissions are `contents: read`
  and nothing else: no `id-token`, so it can get no vibefix token, and the copy job (`vibefix`, with
  `contents: write` and `id-token: write`) is skipped. The job:
  - downloads the gitleaks CLI v8.30.1 `linux_x64` tarball and checks it against the sha256 pinned in
    the file, copied from the release's official checksums file, before extracting it;
  - fetches the default branch's whole history with the run's own `GITHUB_TOKEN`, without the object
    check the copy uses, so a legacy history is still read; `git check-ref-format --branch` judges the
    branch name;
  - scans it with gitleaks' default rules plus `vnpay-hash-secret`, `momo-secret-key` and `zalopay-key`,
    ported from `backend/src/main/resources/scanning/rules.yaml` with their patterns and capture group
    and its `placeholders` filter on the value. VNPay keeps its keywords; MoMo and ZaloPay keep none,
    because gitleaks reads keywords per changed hunk, where the provider's name is often missing.
    `--redact` keeps values out, and `--ignore-gitleaks-allow` keeps `gitleaks:allow` comments from
    silencing a finding;
  - writes the run's summary: the commit scanned, and each finding's rule, file, short commit and line,
    never its value. Its log holds `::error::` lines and one count line,
    `VIBEFIX_SCAN head=<sha> findings=<n>`, and no output of git or gitleaks: a failed scan's reason
    (git's or gitleaks' last lines) goes to the summary. The run fails when n > 0, and when the scan
    itself fails, which prints no count line.

  The scan gates nothing: the Customer still confirms at J7 by themselves, and vibefix handles only the
  head and n, never a finding's file, rule or value. n is the Customer's own run's report, not evidence
  the platform checked: the Customer's repository and its Actions are theirs.

The probe `spike-s6` runs in three parts. Only the author can run it, on GitHub, as an owner of
`vibefix-dev` and with a second account as the Customer.

- **The exchange.** `POST /exchange` on `--exchange-port` (8099 by default) takes `{id_token,
  correlation}`. The probe registers each correlation just before it dispatches the run, and issues one
  token per correlation only when all of these hold:
  - the ID token's RS256 signature verifies against GitHub's key set
    (`https://token.actions.githubusercontent.com/.well-known/jwks`);
  - its issuer is GitHub's, `aud` is exactly `vibefix`, and `exp`, `nbf` and `iat` are valid within 60
    seconds;
  - `repository` and `repository_id` are the correlation's repository, `event_name` is
    `workflow_dispatch`, and `runner_environment` is `github-hosted`;
  - the run read back with the repository's own App has the expected workflow file's `path`, a
    `head_sha` equal to the claim `workflow_sha`, the dispatching App's bot as `triggering_actor`, and the
    correlation in its `display_title`;
  - the workflow file's blob at `workflow_sha` equals `git hash-object` of the published file (CRLF read
    as LF);
  - last, `ref` is that repository's default branch. The blob comes first, so a changed file on another
    branch is refused as `blob`, and the published file on another branch as `ref`.

  Anything else is answered 403 with one reason code, for example `signature`, `blob`, `ref`, `actor`, or
  `replay`, and the run fails without a token. A refusal after the signature, issuer, audience and times
  are verified is kept on its correlation and printed; one before that names no correlation for sure,
  so the probe counts it only. The probe never prints the ID token or the token. A token is revoked when
  its run ends, or when the probe exits, Ctrl+C and `docker stop` included.
- **What each run gets.** The J7 copy: the Intermediary and Working Repositories, contents and workflows
  write. A handover: the Working and Intermediary Repositories, contents and workflows write. The Final
  Merge: the Intermediary Repository, contents read; it pushes the new branch with its own
  `GITHUB_TOKEN`.
- **Tokens.** Every token the probe mints itself is for one check or one run, and revoked after it. The
  probe reads GitHub's answer to each revocation: "token not revoked: HTTP n" fails the run. Tokens are
  narrowed by numeric repository IDs, except those nothing can narrow, as in Spike S4: a repository's
  lookup by name for its ID, the creation of the two repositories, the organization reads, and the
  installations' repository listings.
- **What the probe deletes.** Only the runner's run logs, after it has printed the last 40 lines of a
  failed step to stderr. A Customer's run logs stay in the Customer's repository. A scan run's logs are
  only searched for the count line: no line of them is printed, even when the run fails.
- **What stays on GitHub.** The repositories, branches, and pull requests the probe creates; the
  runner's workflow file; the runs themselves, whose inputs (repository names, branches, SHAs, the
  exchange's address) anyone who can read that repository can read. No input carries a token.

1. **Configure `vibefix-dev`** as an owner. Part b's first check reads each setting back.
   - Settings › Authentication security: require two-factor authentication.
   - Settings › Member privileges: set the base permission to "No permission", and untick private
     repositories under "Repository forking".
   - Settings › Actions › General › Policies: "Selected repositories", with only `vibefix-runner` listed
     (step 2), and "Allow vibefix-dev actions and reusable workflows" (`allowed_actions=local_only`): the
     workflows use none. Under "Workflow permissions", keep "Read repository contents and packages
     permissions", and leave "Allow GitHub Actions to create and approve pull requests" unticked.
   - Set `VIBEFIX_GITHUB_RUNNER_REPOSITORY=vibefix-runner` in `deploy/.env`, and restart the stack with
     both files (`docker compose -f deploy/compose.yaml -f deploy/compose.integrations.yaml up -d --build
     --wait`): `compose.yaml` alone recreates the backend without the Platform App, Merge App and VNPay. With
     it, the dev stack's Job setup and platform guard expect exactly these Actions settings, and the
     daily check expects the Platform App to hold Actions write. Without it, they report both as
     differences. The probe's commands below read it from `deploy/.env` too, as the stack does.
2. **Create the runner.** In `vibefix-dev`, create the private repository `vibefix-runner` and tick "Add
   a README" so that `main` exists. Leave it otherwise empty: part b writes `.github/workflows/s6-copy.yml`
   there and refuses a runner with any other workflow, a default branch other than `main`, or one that
   is public.
3. **Give the Platform App Actions write.** Developer settings › GitHub Apps › the dev Platform App ›
   Permissions › Repository permissions › Actions: "Read and write". Then accept the changed permissions
   on its installation on `vibefix-dev` (Settings › GitHub Apps › Configure). Part b compares the
   registration with AD-10's permissions plus `actions=write`, and fails with "installation has not
   accepted actions write" until the installation accepted it.
4. **The Source App.** A public App ("Any account") with the webhook off:
   - repository permissions: Contents **read**, Metadata **read**, and Actions **read and write**,
     nothing else;
   - no organization permission, no event.

   Save its key as `deploy/secrets/github-source-app-key.pem`, and note the App ID. An App kept from the
   first probe needs Actions added; the Customer then accepts the changed permissions, or part b's check
   3 fails with "installation has not accepted actions write".
5. **As the Customer** (your second account, outside `vibefix-dev`, with two-factor authentication on):
   - create a private repository with a few commits;
   - add `deploy/customer/vibefix.yml` unchanged as `.github/workflows/vibefix.yml` on its default branch;
   - install the Source App on that repository only;
   - for the tampered check, create a branch, for example `tampered`, whose `vibefix.yml` differs by one
     added comment line;
   - for the scan (`--scan`, optional), nothing yet: part b runs with `--scan` once before and once
     after a made-up VNPay value is committed (step 8).
6. **Open the tunnel.** Install cloudflared (`winget install --id Cloudflare.cloudflared`) and run, in a
   terminal of its own:

   ```sh
   cloudflared tunnel --no-autoupdate --url http://localhost:8099
   ```

   It prints an address such as `https://<words>.trycloudflare.com`: that is `--exchange-url`, without a
   path. Keep it running through parts b and c. A quick tunnel gets a new address each time it starts,
   and nobody else can use the exchange: it issues a token only for a run the probe has just dispatched.
   Cloudflare ends TLS at its edge, so each ID token and each token issued passes through Cloudflare in
   clear. Both are short-lived: an ID token lasts minutes, and an issued token is narrowed to the spike's
   repositories and revoked when its run ends. A released design would end TLS at vibefix's own host.
7. **Run part a: the Source App.** The probe starts the backend image under `cli`, like the other probes.
   Run the probe commands from PowerShell, not Git Bash: Git Bash rewrites the arguments that start with
   `/` (`/run/secrets/...` becomes `C:/Program Files/Git/run/secrets/...`, and a `-v` mount gains `;`), so
   the probe finds no key; in Git Bash, prefix the command with `MSYS_NO_PATHCONV=1`. PowerShell 5.1 passes
   them unchanged, `dev,cli` included (2026-11-11).
   So the stack's `postgres` must be up, with its database already migrated by the stack, and
   `--spring.flyway.enabled=false` keeps the probe from migrating your dev database.

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/github-source-app-key.pem:/run/secrets/github-source-app-key:ro -v ./deploy/customer/vibefix.yml:/run/customer/vibefix.yml:ro backend --spring.flyway.enabled=false --vibefix.cli.command=spike-s6 --part=a --source=<Customer>/<private repository> --customer=<Customer> --source-app-id=<Source App ID> --source-app-key-file=/run/secrets/github-source-app-key --customer-workflow=/run/customer/vibefix.yml
   ```

   It checks the registration (Contents and Metadata read, Actions write, no event), the installation and
   its selection (only `--source`), a Contents-read token narrowed to the source (`private=true`, the
   default branch and its head), the Customer's `admin` permission (AD-13), a ref creation, which must be
   refused, and the blob of the Customer's `vibefix.yml` against the published file.
8. **Run part b: the J7 copy on the Customer's Actions.** It needs the dev Platform App as for Spike S4,
   the exchange port published on `127.0.0.1`, and the files above, each mounted read-only.

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -p 127.0.0.1:8099:8099 -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/github-platform-app-key.pem:/run/secrets/github-platform-app-key:ro -v ./deploy/secrets/github-source-app-key.pem:/run/secrets/github-source-app-key:ro -v ./deploy/customer/vibefix.yml:/run/customer/vibefix.yml:ro -v ./deploy/runner:/run/runner:ro backend --spring.flyway.enabled=false --vibefix.cli.command=spike-s6 --vibefix.github.platform-app.id=<App ID> --vibefix.github.platform-app.installation-id=<installation ID> --vibefix.github.platform-app.key-file=/run/secrets/github-platform-app-key --part=b --source=<Customer>/<private repository> --source-app-id=<Source App ID> --source-app-key-file=/run/secrets/github-source-app-key --customer-workflow=/run/customer/vibefix.yml --workflows-dir=/run/runner --exchange-url=https://<words>.trycloudflare.com --tampered-ref=tampered
   ```

   - **The checks before anything is created.** `vibefix-dev`'s settings with the runner (step 1),
     including `members_can_fork_private_repositories`. The Platform App's registration and what its
     installation accepted, and the runner: private, on `main`, Actions enabled, and its
     `.github/workflows/` holding nothing but `s6-copy.yml`, read before the probe writes that file with
     a Platform token narrowed to it. The Source App's registration and installation (with Actions write
     accepted), the source's head, and the Customer's `vibefix.yml` blob.
   - **Only then** does it create `s6-{yyyyMMddHHmm}-intermediary` and `-working` (`--prefix`, letters,
     digits, and hyphens, names them otherwise): private, with `allow_forking=false`, and their features
     off.
   - **The J7 copy.** The Source App dispatches `vibefix.yml` with `operation=copy` on the default branch.
     The run exchanges its ID token and pushes the source's head to both repositories, which the probe
     reads back at that commit. Check 7 prints the run as the probe reads it (path, event, triggering
     actor, head) and what the exchange issued: one token for that run, requested for the two
     repositories, revoked HTTP 204.
   - **The other checks.** Actions read as disabled on both new repositories, and what Free answers to a
     ruleset on the private Intermediary (an answer that names no plan fails). With `--tampered-ref`, the
     same dispatch on that branch must be refused a token, with both repositories read back unchanged
     (a head that cannot be read fails the check). Its changed file is refused as `blob`; `ref` passes
     too, for a branch whose file is the published one. Without `--tampered-ref`, the check is skipped.
     Check 11 gives the duration and billed minutes of every copy run, and their totals.
   - **The gitleaks scan (check 12, "gitleaks on Customer's Actions"), with `--scan`** added to the
     command above (a flag, part b only).
     - **When.** After check 3 passed, whatever checks 1 and 2 answered: the scan creates nothing. The
       Source App dispatches `vibefix.yml` with `operation=scan` and a correlation on the default branch,
       with no other input, and the probe waits as for the other runs. The exchange expects nothing for
       it.
     - **What it reads.** The run as the Customer's file: its `path` must be
       `.github/workflows/vibefix.yml`, and the file's blob at its `head_sha` the published file's, as
       check 3 and the exchange compare it. Then the run's logs, with a Source App token narrowed to the
       source (Actions read), for the one `VIBEFIX_SCAN head=<sha> findings=<n>` line: it must start
       with GitHub's timestamp, appear at most once in each file of the logs, and agree across them. A
       log with no such line is read once more after a poll, since GitHub may still be writing it.
     - **What it prints.** The conclusion, n, the head scanned, the run's `path`, `head_sha` and blob,
       the duration and the billed minutes, and the failed step for a run that did not succeed.
     - **OK** when the run is the published file's, the line is there once with a 40-hex head, and the
       conclusion matches n: success at 0, failure above 0 (a Customer whose history holds a finding sees
       a failed run, and the check still passes). It fails otherwise, for example when the gitleaks
       download does not match its sha256 or the scan itself fails. Without `--scan`, check 12 is skipped
       and nothing is dispatched.
     - **Time and minutes.** The scan shares the job's 15-minute limit and the probe's 20-minute wait.
       It bills the Customer's minutes, which are free on a public repository. It is not in check 11's
       totals: check 12 prints its own duration and billed minutes.
     - **Twice.** Run part b with `--scan` once as the repository is, and record check 12's line
       (`findings=0` on a clean history, conclusion success). Then, as the Customer, commit on the default
       branch a made-up VNPay value, a line `vnp_HashSecret=` followed by 20 letters and digits of your
       own (not a placeholder such as `xxxx…` or `0000…`), and remove it in a later commit: the scan
       reads the whole history, so it still finds it. Run part b with `--scan` again, with a new
       `--prefix`, and record check 12's line once more (`findings=1` or more, conclusion failure, the
       check OK).
   - **Each run** prints which field of the dispatch answer carried its run ID, or that it was found by
     its `run-name` after a 204. A dispatch answered 404 is tried again up to 6 times. A run is polled
     every 10 seconds for up to 20 minutes (the job stops itself at 15); one not completed then is
     cancelled, its token revoked, and its logs left. A billed minute comes from the run's
     `timing.billable` when GitHub gives one, else from its jobs' start and end times; each job is rounded
     up to the minute. The Customer's runs bill the Customer's minutes, and the runner's bill
     `vibefix-dev`'s.
9. **Run part c: the Customer, the handovers, and the Final Merge**, with part b's prefix.

   ```sh
   docker compose -f deploy/compose.yaml run --rm --no-deps -p 127.0.0.1:8099:8099 -e SPRING_PROFILES_ACTIVE=dev,cli -v ./deploy/secrets/github-platform-app-key.pem:/run/secrets/github-platform-app-key:ro -v ./deploy/secrets/github-source-app-key.pem:/run/secrets/github-source-app-key:ro -v ./deploy/customer/vibefix.yml:/run/customer/vibefix.yml:ro -v ./deploy/runner:/run/runner:ro backend --spring.flyway.enabled=false --vibefix.cli.command=spike-s6 --vibefix.github.platform-app.id=<App ID> --vibefix.github.platform-app.installation-id=<installation ID> --vibefix.github.platform-app.key-file=/run/secrets/github-platform-app-key --part=c --prefix=<part b's prefix> --customer=<Customer> --source=<Customer>/<private repository> --source-app-id=<Source App ID> --source-app-key-file=/run/secrets/github-source-app-key --customer-workflow=/run/customer/vibefix.yml --workflows-dir=/run/runner --exchange-url=https://<words>.trycloudflare.com
   ```

   - **The first run** invites the Customer to the Intermediary Repository with `pull`, prints "accept,
     then run again", and exits 0. Accept the invitation as the Customer
     (`https://github.com/vibefix-dev/<prefix>-intermediary/invitations`).
   - **The same command again** reads `read` back on the Intermediary Repository, and `none` or 404 on the
     Working Repository. It then makes two Contents-API commits on the Working Repository's default
     branch, H1 and H2, a child of H1. The Platform App dispatches `s6-copy.yml` on the runner for each:
     the run exchanges its ID token and copies the commit to `handover/<prefix>-1` and `-2`, read back,
     with a pull request each.
   - **Then** it merges pull request 2 with `merge_method: merge` at H2, and prints pull request 1's
     `state` and `merged`; if pull request 1 is still open, it closes it with a comment and reads the
     close back.
   - **Last, the Final Merge.** The Source App dispatches `vibefix.yml` with `operation=final-merge`. The
     run reads the Intermediary Repository's merge commit with a token narrowed to it (contents read) and
     pushes it as the new branch `vibefix/final-<prefix>` with its own `GITHUB_TOKEN`. The probe reads that
     branch back with the Source App, at the merge commit.

   Part c runs once per prefix: if `handover/<prefix>-1` or `vibefix/final-<prefix>` exists, run part b
   again with a new prefix.
10. **Check by hand as the Customer**, signed in as that account:
    - the Intermediary Repository is visible, and "Download ZIP" and `git clone` both work;
    - Fork is disabled, a push is refused, and the Working Repository answers 404;
    - the Actions tab of the Customer's repository shows the `vibefix` runs, their logs show `***` where
      a token was, and the tampered run failed at "Exchange the ID token";
    - each scan run (with `--scan`) ran the `scan` job only, the `vibefix` job skipped; its summary names
      the commit scanned and lists each finding's rule, file, short commit and line with no value; its
      "Scan with gitleaks" log holds the one `VIBEFIX_SCAN head=<sha> findings=<n>` line, `::error::`
      lines, and nothing of git or gitleaks;
    - started by hand from the Actions tab (`operation` scan, every other input empty), a scan runs the
      same way;
    - `vibefix/final-<prefix>` is a branch of the Customer's repository.

    Then invite an account without two-factor authentication to the Intermediary Repository (Settings ›
    Collaborators), and check that it cannot accept the invitation.
11. **Record the Outcome** in the revision's spec (`spec-s6r-spike-customer-actions-oidc.md`):
    - each part's lines, and the field that carried the run ID;
    - the tampered run's reason code, and Free's answer to the ruleset;
    - pull request 1's state after pull request 2 merged;
    - the duration and billed minutes of each run, and whose minutes they were;
    - with `--scan`, both check-12 lines, before and after the made-up VNPay value: the conclusion, n,
      the head, the duration and the billed minutes;
    - the manual checks.

    The correct-course then rewrites AD-10, AD-11, and FR-18 to FR-21 from it, and, with scanning
    dropped, FR-8, FR-65, AD-18, AD-32, Epic 3, and Story 7.4.
12. **Tear down** by hand once the Outcome is recorded. The probe deletes none of this:
    - in `vibefix-dev`, the `s6-…-intermediary` and `s6-…-working` repositories, and every run under the
      runner's Actions;
    - in the Customer's repository, the `vibefix/final-…` and `tampered` branches, the `vibefix` runs, and
      `.github/workflows/vibefix.yml`, and the Customer's installation of the Source App (the Customer's
      Settings › Applications); a made-up key committed for the scan stays in that history, so delete
      the repository itself if it was made for the spike;
    - any `s6-refused-…` branch that part a's ref check reported ACCEPTED on the Customer's repository;
    - the Source App (Developer settings › GitHub Apps › Advanced › Delete) and its key,
      `deploy/secrets/github-source-app-key.pem`;
    - not the runner: since Story 23.3 the platform requires it, so Actions write on the Platform App,
      `VIBEFIX_GITHUB_RUNNER_REPOSITORY` in `deploy/.env`, the `vibefix-runner` repository, and the Actions
      policy "Selected repositories" listing it alone all stay (see "Code Job setup on the dev stack");
    - from the first probe of 2026-11-09, if they still exist: the runner organization
      `vibefix-runner-dev` with its Runner App and `github-runner-app-key.pem`, the Actions secret
      `VIBEFIX_RUNNER_KEY`, and the key pairs `runner-key.pem`, `runner-key.pub.pem`, and
      `runner-result-key.pem` in `deploy/secrets/`.

Exit code 0 means every check passed or was skipped, or part c is waiting for the invitation. 1 means a
check failed, a token was not revoked, the stack is not `dev` (whatever the arguments), the runner is not
configured for parts b and c, or the exchange port cannot be bound. 2 means a wrong invocation (with the
usage), or a file that cannot be read: a key file that holds no usable key, the published
`vibefix.yml`, or `s6-copy.yml` in `--workflows-dir` (the probe names the file's role, never what it
holds).

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
