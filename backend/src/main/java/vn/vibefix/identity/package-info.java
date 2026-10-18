/**
 * Layer 2 (AD-3): accounts and how people get in (AD-19). Customers, Mentors, and Students sign in
 * with GitHub and choose one permanent role; Administrators sign in on their own path. Each request
 * re-reads the account, so a lock takes effect at once. The kept Customer token is encrypted
 * (AD-22), and the module answers for its personal data (AD-24).
 * <p>
 * Public API: {@link vn.vibefix.identity.MentorApprovalStatus}. Sign-in, sessions, and the HTTP
 * endpoints are wiring in {@code identity.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "notification" })
package vn.vibefix.identity;

import org.springframework.modulith.ApplicationModule;
