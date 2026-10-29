package vn.vibefix.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * PRD section 4.0's phase table, X6, SG-66: {@code job.phaseOf} over every state, with recruitment on and off, and
 * with no, one and several counted {@code ACTIVE} Crew rows.
 */
class JobPhaseTest {

	private static final List<String> STATES = List.of("DRAFT", "OPEN", "SELECTING", "AGREEMENT", "STARTING", "ACTIVE",
			"CLOSING", "CLOSED", "CANCELLED");

	@Test
	void everyStateRecruitmentAndCountGivesThePhaseOfTheTable() {
		for (String state : STATES) {
			for (boolean recruiting : new boolean[] { false, true }) {
				for (int counted : new int[] { 0, 1, 3 }) {
					assertThat(JobPhase.phaseOf(state, recruiting, counted))
						.as("%s, recruiting %s, %d counted", state, recruiting, counted)
						.isEqualTo(Optional.ofNullable(expected(state, recruiting, counted)));
				}
			}
		}
	}

	@Test
	void theTableReadsAsTheStoryWritesIt() {
		assertThat(JobPhase.phaseOf("OPEN", false, 0)).contains(JobPhase.FINDING_MENTOR);
		assertThat(JobPhase.phaseOf("SELECTING", false, 0)).contains(JobPhase.FINDING_MENTOR);
		assertThat(JobPhase.phaseOf("AGREEMENT", false, 0)).contains(JobPhase.FINDING_CREW);
		assertThat(JobPhase.phaseOf("AGREEMENT", true, 0)).contains(JobPhase.FINDING_CREW);
		assertThat(JobPhase.phaseOf("ACTIVE", true, 0)).contains(JobPhase.FINDING_CREW);
		assertThat(JobPhase.phaseOf("AGREEMENT", true, 1)).contains(JobPhase.RECRUITING);
		assertThat(JobPhase.phaseOf("STARTING", true, 2)).contains(JobPhase.RECRUITING);
		assertThat(JobPhase.phaseOf("AGREEMENT", false, 1)).contains(JobPhase.AGREEING);
		assertThat(JobPhase.phaseOf("ACTIVE", false, 1)).isEmpty();
		assertThat(JobPhase.phaseOf("STARTING", false, 0)).isEmpty();
		assertThat(JobPhase.phaseOf("CLOSING", true, 1)).isEmpty();
		assertThat(JobPhase.phaseOf("DRAFT", true, 0)).isEmpty();
	}

	@Test
	void aNegativeCountIsRefusedAndAnUnknownStateHasNoPhase() {
		assertThatIllegalArgumentException().isThrownBy(() -> JobPhase.phaseOf("AGREEMENT", true, -1));
		assertThat(JobPhase.phaseOf("UNKNOWN", true, 1)).isEmpty();
	}

	/** The table, written out once more by its rows (PRD section 4.0). */
	private static @Nullable JobPhase expected(String state, boolean recruiting, int counted) {
		if (state.equals("OPEN") || state.equals("SELECTING")) {
			return JobPhase.FINDING_MENTOR;
		}
		boolean crewStates = state.equals("AGREEMENT") || state.equals("STARTING") || state.equals("ACTIVE");
		if (state.equals("AGREEMENT") && counted == 0) {
			return JobPhase.FINDING_CREW;
		}
		if (crewStates && recruiting && counted == 0) {
			return JobPhase.FINDING_CREW;
		}
		if (crewStates && recruiting) {
			return JobPhase.RECRUITING;
		}
		if (state.equals("AGREEMENT")) {
			return JobPhase.AGREEING;
		}
		return null;
	}

}
