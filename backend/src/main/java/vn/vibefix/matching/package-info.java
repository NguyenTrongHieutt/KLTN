/**
 * Layer 7 (AD-3): matching approved Mentors to Job Posts (FR-13). A Mentor applies once per Job with a
 * price, a timeline, and an approach; the FR-13 refusals come from {@code job}'s one definition of the
 * Mentor's free Students and Jobs (AD-33), {@code identity}'s person-link signals (AD-19), and the
 * Source Repository's contributors. J2 closes a Job's Applications, and a locked or revoked Mentor's
 * are withdrawn. Selection, holds, Recommendations, and invitations arrive with later stories.
 * <p>
 * No public API yet; the HTTP endpoints, the listeners, and the personal-data participant are wiring
 * in {@code matching.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "github", "timers", "identity", "notification", "team",
		"product", "job" })
package vn.vibefix.matching;

import org.springframework.modulith.ApplicationModule;
