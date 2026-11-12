package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * AD-11, AD-46 (Story 23.3, deferred-work 634): {@code vibefix.github.runner-repository} is required. Unset, it is
 * an organization difference, with no fallback to "Actions disabled for all". Set, the organization's Actions must
 * run on that one repository only ("Selected repositories" listing it alone), and the runner itself must be
 * private, hold only the published copy workflow, and have no collaborator or invitation; anything else is a
 * difference in plain words. A blank value is unset, as {@code deploy/compose.yaml} passes it; a value that is
 * no repository name is refused.
 */
class RunnerOrganizationTest {

	private static final String RUNNER = "vibefix-runner";

	@TempDir
	Path directory;

	private GitHubStub github;

	private Path key;

	@BeforeEach
	void aStandInGitHub() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.key = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
	}

	@AfterEach
	void stop() {
		this.github.close();
		// JGit keeps pack files open in its window cache; Windows cannot delete the temporary directory
		// until they are released.
		org.eclipse.jgit.lib.RepositoryCache.clear();
		org.eclipse.jgit.internal.storage.file.WindowCache.reconfigure(new org.eclipse.jgit.storage.file.WindowCacheConfig());
	}

	@Test
	void withoutARunnerTheOrganizationDiffersWhateverItsActionsPolicy() {
		assertThat(differences(null)).containsExactly(
				"vibefix.github.runner-repository is not set: GitHub Actions must run on the runner only");

		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add(RUNNER);
		assertThat(differences(null)).as("not even a runner-shaped policy stands in for the setting").containsExactly(
				"vibefix.github.runner-repository is not set: GitHub Actions must run on the runner only");
		assertThat(repositories(null).runnerDifferences()).as("organizationDifferences reports it once").isEmpty();
		assertThat(this.github.seen.stream().filter(request -> request.path().contains("/actions/permissions")))
			.as("without a runner, nothing is compared with it")
			.isEmpty();
	}

	@Test
	void withARunnerActionsRunOnThatRepositoryAlone() {
		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add(RUNNER);

		assertThat(differences(RUNNER)).isEmpty();
		assertThat(differences("Vibefix-Runner")).as("GitHub's names ignore case").isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "all actions", "selected actions", "write tokens", "approvals" })
	void withARunnerOnlyLocalActionsReadTokensAndNoApprovalsMatch(String setting) {
		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add(RUNNER);
		switch (setting) {
			case "all actions" -> this.github.actionsAllowed = "all";
			case "selected actions" -> this.github.actionsAllowed = "selected";
			case "write tokens" -> this.github.defaultWorkflowPermissions = "write";
			default -> this.github.workflowsApprovePullRequests = true;
		}

		assertThat(differences(RUNNER)).singleElement().asString().isEqualTo(switch (setting) {
			case "all actions" -> "GitHub Actions allow all actions instead of the organization's own only (local_only)";
			case "selected actions" -> "GitHub Actions allow selected actions instead of the organization's own only (local_only)";
			case "write tokens" -> "the default workflow permissions are write instead of read";
			default -> "workflows may approve pull requests (can_approve_pull_request_reviews=true) instead of never";
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "another repository too", "another repository instead", "no repository", "none", "all" })
	void withARunnerAnythingElseIsADifference(String policy) {
		this.github.actionsEnabled = "selected";
		switch (policy) {
			case "another repository too" -> this.github.actionsRepositories.addAll(List.of(RUNNER, "job-1-working"));
			case "another repository instead" -> this.github.actionsRepositories.add("job-1-working");
			case "no repository" -> {
			}
			default -> this.github.actionsEnabled = policy;
		}

		List<String> differences = differences(RUNNER);

		assertThat(differences).singleElement().asString().endsWith("instead of the runner vibefix-runner only")
			.startsWith(switch (policy) {
				case "another repository too" -> "GitHub Actions are enabled for vibefix-runner, job-1-working";
				case "another repository instead" -> "GitHub Actions are enabled for job-1-working";
				case "no repository", "none" -> "GitHub Actions are enabled for no repository";
				default -> "GitHub Actions are enabled for all repositories";
			});
	}

	/** AD-11: a new private repository's {@code allow_forking} follows the organization's private forking setting. */
	@Test
	void membersWhoMayForkPrivateRepositoriesAreADifference() {
		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add(RUNNER);
		this.github.membersCanForkPrivateRepositories = true;

		assertThat(differences(RUNNER)).containsExactly(
				"members may fork private repositories (members_can_fork_private_repositories=true) instead of never");
	}

	@Test
	void theRunnerIsPrivateHoldsOnlyThePublishedWorkflowAndHasNobody() throws Exception {
		this.github.runner(RUNNER);

		assertThat(repositories(RUNNER).runnerDifferences()).isEmpty();
		assertThat(this.github.seen).as("its workflows are read with a token narrowed to it with contents read")
			.anyMatch(request -> request.path().equals("/repos/vibefix-dev/vibefix-runner/contents/.github/workflows"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "public", "another workflow", "no workflow", "a collaborator", "an invitation", "missing" })
	void aRunnerThatDriftsIsADifference(String drift) throws Exception {
		this.github.runner(RUNNER);
		switch (drift) {
			case "public" -> this.github.setting(RUNNER, "private", false);
			case "another workflow" -> this.github.push(GitHubStub.ORGANIZATION, RUNNER, "main", ".github/workflows/ci.yml",
					"on: push\n");
			case "no workflow" -> this.github.push(GitHubStub.ORGANIZATION, RUNNER, "main", Map.of("README.md", "# runner\n"),
					List.of(".github"), "No workflow", "ops@example.test");
			case "a collaborator" -> this.github.addCollaborator(RUNNER, "mallory", "write");
			case "an invitation" -> this.github.addInvitation(RUNNER, "eve", "read");
			default -> {
			}
		}

		List<String> differences = repositories(drift.equals("missing") ? "vibefix-runner-gone" : RUNNER).runnerDifferences();

		assertThat(differences).singleElement().asString().isEqualTo(switch (drift) {
			case "public" -> "the runner vibefix-dev/vibefix-runner is public instead of private";
			case "another workflow" -> "the runner vibefix-dev/vibefix-runner holds [ci.yml, s6-copy.yml] in .github/workflows"
					+ " instead of [s6-copy.yml] only";
			case "no workflow" -> "the runner vibefix-dev/vibefix-runner holds [] in .github/workflows instead of [s6-copy.yml] only";
			case "a collaborator" -> "the runner vibefix-dev/vibefix-runner has collaborators [mallory]";
			case "an invitation" -> "the runner vibefix-dev/vibefix-runner has invitations for [eve]";
			default -> "the runner vibefix-dev/vibefix-runner-gone does not exist";
		});
	}

	@Test
	void aBlankRunnerIsUnsetAndAnOwnerOrAPathIsRefused() {
		assertThat(properties("").runnerRepository()).isNull();
		assertThat(properties("  ").runnerRepository()).isNull();
		assertThat(properties(RUNNER).runnerRepository()).isEqualTo(RUNNER);
		for (String wrong : List.of("vibefix-dev/vibefix-runner", "..", ".", "vibefix runner")) {
			assertThatThrownBy(() -> properties(wrong)).as(wrong)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("vibefix.github.runner-repository");
		}
		assertThat(new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev", null, null).runnerRepository())
			.as("the shorter constructors leave it unset")
			.isNull();
		assertThat(new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev", null, null, null)
			.runnerRepository()).isNull();
	}

	private List<String> differences(@Nullable String runner) {
		return repositories(runner).organizationDifferences();
	}

	private GitHubPlatformRepositories repositories(@Nullable String runner) {
		Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);
		GitHubAppsClient apps = new GitHubAppsClient(properties(runner), clock);
		return new GitHubPlatformRepositories(apps, (type, input) -> {
			throw new UnsupportedOperationException("no sandbox job here: " + type);
		}, clock);
	}

	private GitHubProperties properties(@Nullable String runner) {
		return new GitHubProperties(this.github == null ? URI.create("https://api.github.com") : this.github.apiUrl(),
				this.github == null ? null : this.github.gitUrl(), GitHubStub.ORGANIZATION,
				new AppProperties(123456L, GitHubStub.INSTALLATION, this.key), null, null, runner);
	}

}
