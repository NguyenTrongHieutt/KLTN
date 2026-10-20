/**
 * Layer 8 (AD-3): Milestone Handovers and their Delivery Reports (FR-10, FR-32). The Mentor hands a
 * Funded or Revision Requested Milestone over once its preflight passes: every Work Item of it in "Xong"
 * through the Mentor's approval or explained by a note, and each Acceptance Criterion marked "Đạt" or
 * "Chưa đạt" with a note, all FR-47 checked; {@code job} applies M2 with the handover as its evidence
 * (AD-4). An Assessment Job hands over its Assessment Report at the version current then, which never
 * changes afterwards (SG-25). The Customer, the Mentor, and the active Crew read the Delivery Report; the
 * Customer's never names a Work Item (AD-39). The Mentor's marks are a statement, never a criterion done
 * (AD-33). The Customer accepts a handover, with {@code payment} writing the split release in the same
 * transaction (AD-5), or asks for a revision within the Revision Rounds (FR-33, FR-34); left undecided,
 * it is reminded twice and then accepted automatically by a pausable Deadline (FR-35, AD-7).
 * <p>
 * After an Assessment's acceptance the Mentor drafts follow-up Jobs from the Report's numbered
 * recommendations, which the Customer opens as Draft Job Posts or discards (FR-11).
 * <p>
 * Public API: {@link vn.vibefix.delivery.HandoverFacts}, the Milestones handed over, for Portfolio
 * Entries (FR-50). The HTTP endpoints, the listeners that open the handover and follow-up to-dos, and
 * the personal-data participant are wiring in {@code delivery.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "timers", "scanning", "job",
		"work", "payment" })
package vn.vibefix.delivery;

import org.springframework.modulith.ApplicationModule;
