package vn.vibefix.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.vibefix.github.ActionsRuns.Dispatch;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.RunStatus;
import vn.vibefix.github.GitHubApps.App;

/**
 * Story 23.6a: what a dispatch must name, and how GitHub's run statuses read. A dispatch names its App, a Source App
 * run its installation and no other run; a copy names the head the Customer confirmed and no other operation; and no
 * grant names the repository the run is in, for the Source App the Customer's own.
 */
class ActionsRunsTest {

	private static final long SOURCE = 4001;

	private static final long INSTALLATION = 77;

	private static final long WORKING = 9001;

	private static final long INTERMEDIARY = 9002;

	private static final String SHA = "1f".repeat(20);

	@Test
	void aSourceCopyNamesItsInstallationAndTheConfirmedHead() {
		Dispatch copy = new Dispatch(App.SOURCE, INSTALLATION, Operation.COPY, SOURCE, "vibefix.yml", Map.of("sha", SHA),
				Set.of(WORKING, INTERMEDIARY), SHA);

		assertThat(copy.workflowPath()).isEqualTo(".github/workflows/vibefix.yml");
		assertThat(copy.grant()).containsExactlyInAnyOrder(WORKING, INTERMEDIARY);
		assertThat(new Dispatch(App.PLATFORM, null, Operation.HANDOVER_COPY, 9, "s6-copy.yml", Map.of(), Set.of(WORKING),
				null).app()).isEqualTo(App.PLATFORM);
	}

	@Test
	void onlyASourceRunNamesAnInstallation() {
		assertThatThrownBy(() -> new Dispatch(App.SOURCE, null, Operation.COPY, SOURCE, "vibefix.yml", Map.of(),
				Set.of(WORKING), SHA)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Dispatch(App.SOURCE, 0L, Operation.COPY, SOURCE, "vibefix.yml", Map.of(),
				Set.of(WORKING), SHA)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Dispatch(App.PLATFORM, INSTALLATION, Operation.HANDOVER_COPY, 9, "s6-copy.yml",
				Map.of(), Set.of(WORKING), null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aCopyNamesTheConfirmedHeadAndNoOtherOperationDoes() {
		assertThatThrownBy(() -> new Dispatch(App.SOURCE, INSTALLATION, Operation.COPY, SOURCE, "vibefix.yml", Map.of(),
				Set.of(WORKING), null)).as("a copy without its confirmed head would skip the sha check")
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Dispatch(App.PLATFORM, null, Operation.COPY, 9, "vibefix.yml", Map.of(), Set.of(WORKING),
				null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Dispatch(App.SOURCE, INSTALLATION, Operation.COPY, SOURCE, "vibefix.yml", Map.of(),
				Set.of(WORKING), "not-a-sha")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Dispatch(App.PLATFORM, null, Operation.HANDOVER_COPY, 9, "s6-copy.yml", Map.of(),
				Set.of(WORKING), SHA)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void noGrantNamesTheRepositoryTheRunIsIn() {
		assertThatThrownBy(() -> new Dispatch(App.SOURCE, INSTALLATION, Operation.COPY, SOURCE, "vibefix.yml", Map.of(),
				Set.of(WORKING, SOURCE), SHA)).as("the Customer's own repository").isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Dispatch(App.PLATFORM, null, Operation.HANDOVER_COPY, 9, "s6-copy.yml", Map.of(),
				Set.of(WORKING, 9L), null)).as("the runner").isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "{0} reads as {1}")
	@CsvSource({ "completed, COMPLETED", "in_progress, IN_PROGRESS", "queued, QUEUED", "waiting, QUEUED",
			"requested, QUEUED", "pending, QUEUED", "something_new, QUEUED" })
	void gitHubsRunStatusesRead(String status, RunStatus expected) {
		assertThat(RunStatus.of(status)).isEqualTo(expected);
	}

	@Test
	void noStatusReadsQueued() {
		assertThat(RunStatus.of(null)).isEqualTo(RunStatus.QUEUED);
	}

}
