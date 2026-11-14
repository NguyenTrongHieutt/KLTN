package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Story 23.9a (FR-37, AD-11, R1, D7): the Final Merge runs on the Customer's own GitHub Actions, so nothing live
 * names the Merge App machinery it stranded. Gone from {@code backend/src/main} outside its Flyway history, from
 * {@code frontend/src} outside its tests, and from {@code deploy}:
 * <ul>
 * <li>the bindings and the install surface: {@code MergeAppBindings}, {@code FinalMergeTokens},
 * {@code bindReturn}, {@code bindEvent}, {@code removeIfUnneeded}, the Final Merge's own
 * {@code install-link}, {@code merge-app/setup} and {@code MERGE_APP_WRONG_REPOSITORY};</li>
 * <li>the 2-day reminder: {@code FinalMergeReminder} and {@code repo.final_merge_reminder};</li>
 * <li>the trial merge: {@code FinalMergeTrialJobHandler}, {@code FINAL_MERGE_TRIAL} and {@code trialMerge};</li>
 * <li>the branch vibefix pushed and merged itself: {@code BRANCH_PREFIX} and {@code final_merge.pull_url};</li>
 * <li>the four retired notifications and the widened-selection warning.</li>
 * </ul>
 * Tests may name them, a migration test seeds them, and {@code .backups} copies keep them. The Source App keeps its
 * own install link and single-use state (Story 23.5), and what Story 23.9b retires stays: {@code MergeApp},
 * {@code GitHubMergeApp}, {@code App.MERGE}, {@code repo.merge_installation} and the Final Merge's bash harness.
 */
class RetiredMergeAppFinalMergeTest {

	static final Pattern RETIRED = Pattern.compile("MergeAppBindings|FinalMergeTokens|FinalMergeReminder"
			+ "|FinalMergeTrialJobHandler|FINAL_MERGE_TRIAL|trialMerge|TrialMerge|MERGE_APP_WRONG_REPOSITORY"
			+ "|merge_app_selection_widened|merge_app_request_refused|final_merge_authorization_pending"
			+ "|final_merge_authorization_reminder|final_merge_authorized|final_merge_reminder|BRANCH_PREFIX"
			+ "|merge-app/setup|final-merge/install-link|bindReturn|bindEvent|removeIfUnneeded|final_merge\\.pull_url");

	@Test
	void nothingLiveNamesTheMergeAppMachineryTheRunReplaced() {
		List<Path> files = new ArrayList<>(RetiredScanningTest.liveFiles(RetiredScanningTest.BACKEND_MAIN));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.FRONTEND_SRC));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.DEPLOY));
		files.removeIf(RetiredLinkingTokenTest::isBackup);

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "i18n", "vi.json")))
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "features", "job", "closure", "FinalMergeStep.tsx")))
			.anyMatch(file -> file.endsWith(Path.of("repo", "internal", "FinalMergeOperation.java")))
			.anyMatch(file -> file.endsWith(Path.of("deploy", "customer", "vibefix.yml")));
		assertThat(files.stream().flatMap(file -> violations(file, read(file)).stream()).toList()).isEmpty();
	}

	@Test
	void theRuleFindsEachRetiredNameAndLeavesWhatStaysAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("private final MergeAppBindings bindings;", "this.tokens = new FinalMergeTokens();",
				"class FinalMergeReminder implements DeadlineHandler {", "FinalMergeTrialJobHandler.TYPE",
				"static final String TYPE = \"FINAL_MERGE_TRIAL\";", "app.trialMerge(source, branch, intermediary)",
				"record TrialMerge(boolean clean)", "RepoReason.MERGE_APP_WRONG_REPOSITORY",
				"\"repo.merge_app_selection_widened\"", "\"repo.merge_app_request_refused\"",
				"\"repo.final_merge_authorization_pending\"", "\"repo.final_merge_authorization_reminder\"",
				"\"repo.final_merge_authorized\"", "kind = 'repo.final_merge_reminder'",
				"FinalMergeOperation.BRANCH_PREFIX + id", "POST /api/v1/jobs/{jobId}/final-merge/install-link",
				"GET /api/v1/github/merge-app/setup", "SELECT final_merge.pull_url FROM repo.final_merge",
				"this.bindings.bindReturn(authorization, installationId)", "this.bindings.bindEvent(id, requester)",
				"bindings.removeIfUnneeded(installation)")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("import vn.vibefix.github.MergeApp;", "class GitHubMergeApp implements MergeApp {",
				"App.MERGE", "repo.merge_installation", "repo.final_merge_deadline", "repo.final_merge_completed",
				"repo.final_branch_ready", "FinalMerges.branch(jobId)", "Operation.FINAL_MERGE",
				"JobBusy.Purpose.FINAL_MERGE", "record SourceAppInstallLinkView(String url) {",
				"@PostMapping(\"/source-app/install-link\")", "SET state_hash = NULL")) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
	}

	private static List<String> violations(Path file, String text) {
		List<String> violations = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int number = 0; number < lines.length; number++) {
			if (RETIRED.matcher(lines[number]).find()) {
				violations.add(file + ":" + (number + 1) + ": " + lines[number].strip());
			}
		}
		return violations;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
