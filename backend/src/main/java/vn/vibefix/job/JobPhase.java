package vn.vibefix.job;

import java.util.Optional;

/**
 * A Job's phase (PRD section 4.0, X6, AD-4, AD-33, AD-41): a label derived from its state, its Crew recruitment
 * setting and its counted {@code ACTIVE} Crew rows, which "Tìm việc" shows while a Job looks for people. A phase is
 * never a state: no transition, guard, Deadline or fixture reads one; only the listing, MEN-3 and Recommendations do,
 * and the transition table is unchanged. The SPA words each one.
 */
public enum JobPhase {

	/** "Đang tìm người hướng dẫn": Open or Selecting. */
	FINDING_MENTOR,

	/** "Đang tìm nhóm thực hiện": Agreement with no counted row, or Starting or Active recruiting with none. */
	FINDING_CREW,

	/** "Đang tuyển thêm thành viên": recruiting with at least one counted row in Agreement, Starting or Active. */
	RECRUITING,

	/** "Đang thống nhất": Agreement, not recruiting, with at least one counted row. */
	AGREEING;

	/**
	 * {@code job.phaseOf} (X6, SG-66): a pure function of its arguments, tested over the whole phase table.
	 * @param state the Job state's name, for example {@code AGREEMENT}
	 * @param recruiting the Mentor's Crew recruitment is on
	 * @param countedActive the Job's {@code ACTIVE} Crew rows whose accounts are unlocked ({@code identity.lockedAmong})
	 * @return the phase; empty where the state label stands alone (Draft, Starting or Active without recruitment,
	 * Closing, Closed, Cancelled)
	 * @throws IllegalArgumentException for a negative count
	 */
	public static Optional<JobPhase> phaseOf(String state, boolean recruiting, int countedActive) {
		if (countedActive < 0) {
			throw new IllegalArgumentException("A count of Crew rows is never negative");
		}
		return switch (state) {
			case "OPEN", "SELECTING" -> Optional.of(FINDING_MENTOR);
			case "AGREEMENT" -> Optional.of(countedActive == 0 ? FINDING_CREW : recruiting ? RECRUITING : AGREEING);
			case "STARTING", "ACTIVE" -> recruiting ? Optional.of(countedActive == 0 ? FINDING_CREW : RECRUITING)
					: Optional.empty();
			default -> Optional.empty();
		};
	}

}
