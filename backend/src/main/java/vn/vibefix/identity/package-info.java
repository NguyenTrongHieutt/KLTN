/**
 * Layer 2 (AD-3): accounts and how people get in (AD-19). Each person has one account, signs in with
 * Google, and links GitHub when an action needs it; Administrators sign in on their own path. Each request
 * re-reads the account, so a lock takes effect at once, and nobody is approved or reviewed by an
 * Administrator: trust comes from what the System verifies (Story 19.7). The kept GitHub token is
 * encrypted (AD-22), and the module answers for its personal data (AD-24). A person verifies their student
 * status with a school email code on the {@code settings} allowlist, and {@code timers} reminds them before
 * it expires and ends it on 30 September (FR-5).
 * <p>
 * Public API: {@link vn.vibefix.identity.ContactVisibility}, {@link vn.vibefix.identity.StudentVerification},
 * {@link vn.vibefix.identity.Capabilities} (what an account can show for itself, read on every call),
 * {@link vn.vibefix.identity.ActiveAccount} (the gate of every user command that creates no new work),
 * {@link vn.vibefix.identity.PersonSignals} with {@link vn.vibefix.identity.PersonSignalMatched},
 * {@link vn.vibefix.identity.AccountLocked}, the public profile searches
 * {@link vn.vibefix.identity.StudentDirectory} and {@link vn.vibefix.identity.MentorDirectory}, PUB-4's public
 * facts of a person {@link vn.vibefix.identity.PublicProfiles} (Story 21.3),
 * {@link vn.vibefix.identity.CustomerRepositories} (repositories read with a Customer's kept token),
 * {@link vn.vibefix.identity.Administrators}, {@link vn.vibefix.identity.Notices},
 * {@link vn.vibefix.identity.CustomerNames}, and the GitHub link (Story 19.2): the gate of the commands that
 * create work and of reading Job Posts {@link vn.vibefix.identity.GitHubGate},
 * {@link vn.vibefix.identity.GitHubLinks}, and the port {@link vn.vibefix.identity.GitHubLinkParticipant} with
 * {@link vn.vibefix.identity.GitHubLinkBlocker}, which
 * {@code product}, {@code job}, and {@code matching} implement so that {@code identity} depends on none of
 * them. Sign-in, linking, sessions, and the HTTP endpoints are wiring in {@code identity.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "files", "settings", "catalog", "github", "timers",
		"notification", "operations" })
package vn.vibefix.identity;

import org.springframework.modulith.ApplicationModule;
