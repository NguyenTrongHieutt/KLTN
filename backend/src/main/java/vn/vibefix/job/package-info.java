/**
 * Layer 6 (AD-3): Jobs (FR-9) and their lifecycle (PRD section 4.0, AD-4). A Customer drafts a Job on
 * one of their Products by answering guided plain-language questions, choosing Job Types and Skills,
 * and attaching screenshots, then publishes it as a Job Post (J1), which returns to Draft when
 * withdrawn or when its selection period lapses (J2). One transition table holds J1 to J10 and M1 to
 * M8, and {@code JobLifecycle} is the only code that changes a state.
 * <p>
 * Mentors browse Open Job Posts that are not hidden (FR-12) and see whether they can take
 * new work (FR-13, AD-33).
 * <p>
 * The Job's Mentor recruits its Crew from J4 until J8 (Story 21.4): the setting lives on the Job, and
 * {@link vn.vibefix.job.CrewRecruitment} is how {@code matching} sends a Crew Application under the Job row lock.
 * <p>
 * Each release creates its Milestone's split, with its parties fixed (Story 22.1): {@link vn.vibefix.job.MilestoneSplits}
 * reads it, even after the Job's data is gone, and {@link vn.vibefix.job.MilestoneSplitFinalized} says it is final. The
 * Mentor drafts and edits it, its parties confirm it, and four non-pausable Deadlines default and finalize it (Story 22.2);
 * a Dispute or a freeze never pauses it, and it carries on after the Job ends. A Split Resolution, {@code dispute}'s, holds,
 * resumes and decides a split through the same port under the Job row lock (Story 22.3).
 * <p>
 * Public API: {@link vn.vibefix.job.JobTransitioned} and {@link vn.vibefix.job.JobPosts}. The HTTP
 * endpoints, the transition steps, the selection lapse, the recipient lookup, the Skill-name
 * listener, and the screenshot read policy are wiring in {@code job.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "files", "identity", "notification",
		"operations", "settings", "timers", "scanning", "product" })
package vn.vibefix.job;

import org.springframework.modulith.ApplicationModule;
