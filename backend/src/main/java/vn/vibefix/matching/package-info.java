/**
 * Layer 7 (AD-3): matching people to Job Posts (FR-13). Any user with GitHub linked bids once per Job with a price,
 * a timeline, an approach, and at most five proof items, files that {@code files} stores or {@code https} links
 * (Story 21.2); the FR-13 refusals come from {@code job}'s one definition of the Mentor's Jobs (AD-33),
 * {@code identity}'s person-link signals (AD-19), and the Source Repository's contributors, read before the bid's
 * transaction. J2 closes a Job's bids, and a locked bidder's are withdrawn. The Job's Customer compares the bids as
 * Bid cards, oldest first, by the bidders' Verified facts, what they posted, and the Fit metrics, which
 * {@code matching.fitMetrics} alone defines (AD-17, Story 21.3); searches GitHub-verified users and invites them to
 * bid within daily counters (FR-14); J2 and J3 lapse the invitations. While a Job's Mentor recruits (Story 21.4),
 * users apply to its Crew with a note, Skills and proof (Crew Applications, AD-43), sent through
 * {@code job.requireRecruiting} under the Job row lock; the Mentor reads them as Applicant cards and declines one, and
 * an application lapses after the Job's copied days or when {@code CrewRecruitmentClosed} arrives.
 * <p>
 * No public API yet; the HTTP endpoints, the listeners, the proof file read policy, and the personal-data participant
 * are wiring in {@code matching.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "github", "timers", "identity", "notification",
		"product", "job", "files" })
package vn.vibefix.matching;

import org.springframework.modulith.ApplicationModule;
