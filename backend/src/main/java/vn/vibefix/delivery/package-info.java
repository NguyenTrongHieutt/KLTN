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
 * A Code Job's handover (Story 8.2) needs at least one commit since the previous Handover Commit, and no
 * change under {@code .github/workflows/}; vibefix scans no code (Story 23.1). Since Story 23.8a it is
 * asynchronous: "Gửi bàn giao" keeps the checked handover pending and {@code repo} copies the Handover
 * Commit to {@code handover/{id}} of the Intermediary on the runner's GitHub Actions, which reports every path
 * the range changed and whether {@code .github/workflows/} differs. Only then does M2 apply, with the Sensitive
 * Changes classified from that full list; a refused copy, or a list that cannot be read, ends the submission with
 * a named reason and records no handover. The Delivery Report lists the Sensitive Changes, the Pages updated, the
 * commits, and the pull request's changes and {@code handover/{id}} branch on GitHub (AD-11, AD-33, AD-46).
 * Since vibefix scans no team code, "Gửi bàn giao" carries the Mentor's N-33 duty confirmation at the version in
 * force and records it with the handover, and the Customer reads the same fact before they accept (FR-25, FR-33,
 * R28, Story 23.8b).
 * <p>
 * After an Assessment's acceptance the Mentor drafts follow-up Jobs from the Report's numbered
 * recommendations, which the Customer opens as Draft Job Posts or discards (FR-11).
 * <p>
 * Public API: {@link vn.vibefix.delivery.HandoverFacts}, the Milestones handed over, for Portfolio
 * Entries (FR-50). The HTTP endpoints, the listeners that open the handover and follow-up to-dos, and
 * the personal-data participant are wiring in {@code delivery.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "operations", "timers",
		"scanning", "job", "work", "payment", "repo", "files" })
package vn.vibefix.delivery;

import org.springframework.modulith.ApplicationModule;
