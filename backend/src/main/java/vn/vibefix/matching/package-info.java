/**
 * Layer 7 (AD-3): matching Mentors to Job Posts (FR-13). A Mentor applies once per Job with a
 * price, a timeline, and an approach; the FR-13 refusals come from {@code job}'s one definition of the
 * Mentor's free Students and Jobs (AD-33), {@code identity}'s person-link signals (AD-19), and the
 * Source Repository's contributors. J2 closes a Job's Applications, and a locked Mentor's
 * are withdrawn. The Job's Customer searches Mentors and invites them to apply within daily counters
 * (FR-14); J2 and J3 lapse the invitations. Recommendations arrive with Story 3.10.
 * <p>
 * No public API yet; the HTTP endpoints, the listeners, and the personal-data participant are wiring
 * in {@code matching.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "github", "timers", "identity", "notification",
		"team", "product", "job" })
package vn.vibefix.matching;

import org.springframework.modulith.ApplicationModule;
