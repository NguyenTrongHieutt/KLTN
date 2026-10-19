package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import vn.vibefix.matching.internal.Recommendations.Candidate;
import vn.vibefix.matching.internal.Recommendations.JobTypeMatch;

/** AD-17's order and FR-14's place for a new Mentor, with no database. */
class RecommendationsTest {

	private static final UUID S1 = UUID.fromString("0192b000-0000-7000-8001-000000000001");

	private static final UUID S2 = UUID.fromString("0192b000-0000-7000-8001-000000000002");

	private static final UUID S3 = UUID.fromString("0192b000-0000-7000-8001-000000000003");

	@Test
	void sharedSkillsThenHistoryThenFewerActiveJobsThenIdAsPostgresqlOrdersIt() {
		Candidate three = candidate("00000000-0000-0000-0000-00000000000a", List.of(S1, S2, S3), 0, 2, false);
		Candidate twoWithHistory = candidate("00000000-0000-0000-0000-00000000000b", List.of(S1, S2), 2, 2, false);
		Candidate twoBusy = candidate("00000000-0000-0000-0000-00000000000c", List.of(S1, S2), 0, 2, false);
		Candidate twoIdle = candidate("00000000-0000-0000-0000-00000000000d", List.of(S1, S2), 0, 0, false);
		// The high bit set: Java's signed UUID order would put it first, PostgreSQL's puts it last.
		Candidate twoIdleHighId = candidate("f0000000-0000-0000-0000-00000000000d", List.of(S1, S2), 0, 0, false);
		Candidate twoIdleLowId = candidate("10000000-0000-0000-0000-00000000000d", List.of(S1, S2), 0, 0, false);

		List<Candidate> ordered = Recommendations
			.order(List.of(twoIdleHighId, twoBusy, twoIdle, three, twoIdleLowId, twoWithHistory));

		assertThat(ordered).containsExactly(three, twoWithHistory, twoIdle, twoIdleLowId, twoIdleHighId, twoBusy);
	}

	@Test
	void theBestPlacedNewMentorMovesUpToFifth() {
		List<Candidate> experienced = new ArrayList<>(IntStream.range(0, 6)
			.mapToObj(i -> candidate("00000000-0000-0000-0000-00000000000" + i, List.of(S1, S2), 1, 0, false))
			.toList());
		Candidate firstNew = candidate("00000000-0000-0000-0000-000000000007", List.of(S1), 0, 0, true);
		Candidate secondNew = candidate("00000000-0000-0000-0000-000000000008", List.of(S1), 0, 0, true);
		experienced.add(secondNew);
		experienced.add(firstNew);

		List<Candidate> ordered = Recommendations.order(experienced);

		assertThat(ordered.get(4)).isEqualTo(firstNew);
		assertThat(ordered.indexOf(secondNew)).as("only the best-placed one moves").isEqualTo(7);
		assertThat(ordered).hasSize(8);
	}

	@Test
	void aNewMentorAlreadyInTheTopFiveStaysWhereTheOrderPutsThem() {
		Candidate newBest = candidate("00000000-0000-0000-0000-000000000001", List.of(S1, S2, S3), 0, 0, true);
		List<Candidate> list = new ArrayList<>(List.of(newBest));
		IntStream.range(2, 9)
			.forEach(i -> list.add(candidate("00000000-0000-0000-0000-00000000000" + i, List.of(S1), 0, 0, false)));
		Candidate laterNew = candidate("00000000-0000-0000-0000-000000000009", List.of(S1), 0, 0, true);
		list.add(laterNew);

		List<Candidate> ordered = Recommendations.order(list);

		assertThat(ordered.getFirst()).isEqualTo(newBest);
		assertThat(ordered.getLast()).isEqualTo(laterNew);
	}

	private static Candidate candidate(String id, List<UUID> shared, int history, int activeJobs, boolean newcomer) {
		List<JobTypeMatch> matches = history == 0 ? List.of() : List.of(new JobTypeMatch("BUG_FIX", history));
		return new Candidate(UUID.fromString(id), shared, shared, matches, activeJobs, newcomer);
	}

}
