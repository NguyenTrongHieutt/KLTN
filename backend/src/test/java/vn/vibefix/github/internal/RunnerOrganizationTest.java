package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * Spike S6 revision (AD-11): {@code vibefix.github.runner-repository} unset, the organization's Actions
 * must be disabled, exactly as before; set, they must run on that one repository only ("Selected
 * repositories" listing it alone), and anything else is a difference in plain words. A blank value is
 * unset, as {@code deploy/compose.yaml} passes it; a value that is no repository name is refused.
 */
class RunnerOrganizationTest {

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
	}

	@Test
	void withoutARunnerActionsMustBeDisabledAsBefore() {
		assertThat(differences(null)).isEmpty();

		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add("vibefix-runner");
		assertThat(differences(null)).containsExactly("GitHub Actions are enabled for selected repositories");
		this.github.actionsEnabled = "all";
		assertThat(differences(null)).containsExactly("GitHub Actions are enabled for all repositories");
		this.github.actionsAllowed = "all";
		this.github.defaultWorkflowPermissions = "write";
		this.github.workflowsApprovePullRequests = true;
		this.github.actionsEnabled = "none";
		assertThat(differences(null)).as("without a runner, nothing but enabled_repositories counts").isEmpty();
		assertThat(this.github.seen.stream().filter(request -> request.path().endsWith("/actions/permissions/repositories")
				|| request.path().endsWith("/actions/permissions/workflow")))
			.as("the selected repositories and the workflow permissions are never read without a runner")
			.isEmpty();
	}

	@Test
	void withARunnerActionsRunOnThatRepositoryAlone() {
		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add("vibefix-runner");

		assertThat(differences("vibefix-runner")).isEmpty();
		assertThat(differences("Vibefix-Runner")).as("GitHub's names ignore case").isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "all actions", "selected actions", "write tokens", "approvals" })
	void withARunnerOnlyLocalActionsReadTokensAndNoApprovalsMatch(String setting) {
		this.github.actionsEnabled = "selected";
		this.github.actionsRepositories.add("vibefix-runner");
		switch (setting) {
			case "all actions" -> this.github.actionsAllowed = "all";
			case "selected actions" -> this.github.actionsAllowed = "selected";
			case "write tokens" -> this.github.defaultWorkflowPermissions = "write";
			default -> this.github.workflowsApprovePullRequests = true;
		}

		assertThat(differences("vibefix-runner")).singleElement().asString().isEqualTo(switch (setting) {
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
			case "another repository too" -> this.github.actionsRepositories.addAll(List.of("vibefix-runner", "job-1-working"));
			case "another repository instead" -> this.github.actionsRepositories.add("job-1-working");
			case "no repository" -> {
			}
			default -> this.github.actionsEnabled = policy;
		}

		List<String> differences = differences("vibefix-runner");

		assertThat(differences).singleElement().asString().endsWith("instead of the runner vibefix-runner only")
			.startsWith(switch (policy) {
				case "another repository too" -> "GitHub Actions are enabled for vibefix-runner, job-1-working";
				case "another repository instead" -> "GitHub Actions are enabled for job-1-working";
				case "no repository", "none" -> "GitHub Actions are enabled for no repository";
				default -> "GitHub Actions are enabled for all repositories";
			});
	}

	@Test
	void aBlankRunnerIsUnsetAndAnOwnerOrAPathIsRefused() {
		assertThat(properties("").runnerRepository()).isNull();
		assertThat(properties("  ").runnerRepository()).isNull();
		assertThat(properties("vibefix-runner").runnerRepository()).isEqualTo("vibefix-runner");
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
		Clock clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
		GitHubAppsClient apps = new GitHubAppsClient(properties(runner), clock);
		return new GitHubPlatformRepositories(apps, (type, input) -> {
			throw new UnsupportedOperationException("no sandbox job here: " + type);
		}, Duration.ofMillis(10), Duration.ofSeconds(1), clock).organizationDifferences();
	}

	private GitHubProperties properties(@Nullable String runner) {
		return new GitHubProperties(this.github == null ? URI.create("https://api.github.com") : this.github.apiUrl(),
				this.github == null ? null : this.github.gitUrl(), GitHubStub.ORGANIZATION,
				new AppProperties(123456L, GitHubStub.INSTALLATION, this.key), null, null, runner);
	}

}
