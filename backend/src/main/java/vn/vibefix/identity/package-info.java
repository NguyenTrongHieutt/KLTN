/**
 * Layer 2 (AD-3): accounts and how people get in (AD-19). Customers, Mentors, and Students sign in
 * with GitHub and choose one permanent role; Administrators sign in on their own path. Each request
 * re-reads the account, so a lock takes effect at once. The kept Customer token is encrypted
 * (AD-22), and the module answers for its personal data (AD-24). Students verify their student
 * status with a school email code on the {@code settings} allowlist or a student card an
 * Administrator approves, kept in {@code files}, and {@code timers} reminds them
 * before it expires and ends it on 30 September (FR-5).
 * <p>
 * Public API: {@link vn.vibefix.identity.MentorApprovalStatus}, {@link vn.vibefix.identity.ContactVisibility},
 * {@link vn.vibefix.identity.StudentVerification}, {@link vn.vibefix.identity.StudentCardQueue} and
 * {@link vn.vibefix.identity.Administrators}. Sign-in, sessions, and the HTTP endpoints are
 * wiring in {@code identity.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "files", "settings", "timers", "notification" })
package vn.vibefix.identity;

import org.springframework.modulith.ApplicationModule;
