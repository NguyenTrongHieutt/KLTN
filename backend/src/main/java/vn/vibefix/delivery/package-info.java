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
 * No public API yet; the HTTP endpoints, the listener that opens the handover to-do, and the
 * personal-data participant are wiring in {@code delivery.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "timers", "scanning", "job",
		"work", "payment" })
package vn.vibefix.delivery;

import org.springframework.modulith.ApplicationModule;
