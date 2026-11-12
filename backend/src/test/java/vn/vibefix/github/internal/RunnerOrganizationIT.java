package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.support.IntegrationTest;

/**
 * Spike S6 revision (AD-10, AD-11, AD-46): with {@code vibefix.github.runner-repository} set, the application's
 * organization read-back (Job setup's and the platform guard's) wants the organization's Actions on that
 * one repository only, the organization's own actions only, read tokens by default, and no pull request
 * approvals by workflows; the daily configuration check wants the Platform App to hold Actions write.
 * Anything else, and a Platform App without Actions write, is a difference. GitHub is {@link GitHubStub}.
 */
class RunnerOrganizationIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	static {
		try {
			DIRECTORY = Files.createTempDirectory("runner-organization-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private PlatformRepositories repositories;

	@Autowired
	private AppConfigurationCheck check;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.webhook-url", () -> "https://vibefix.test/webhooks");
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> "vibefix-runner");
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void actionsOnTheRunnerOnly() {
		GITHUB.actionsEnabled = "selected";
		GITHUB.actionsRepositories.clear();
		GITHUB.actionsRepositories.add("vibefix-runner");
	}

	@Test
	void theRunnerAloneMatchesAd11() {
		assertThat(this.repositories.organizationDifferences()).isEmpty();
	}

	@Test
	void anotherRepositoryNoneOrAllIsADifference() {
		GITHUB.actionsRepositories.add("job-1-working");
		assertThat(this.repositories.organizationDifferences())
			.containsExactly("GitHub Actions are enabled for vibefix-runner, job-1-working instead of the runner vibefix-runner only");

		GITHUB.actionsEnabled = "none";
		assertThat(this.repositories.organizationDifferences())
			.containsExactly("GitHub Actions are enabled for no repository instead of the runner vibefix-runner only");

		GITHUB.actionsEnabled = "all";
		assertThat(this.repositories.organizationDifferences())
			.containsExactly("GitHub Actions are enabled for all repositories instead of the runner vibefix-runner only");
	}

	@Test
	void foreignActionsWriteTokensOrApprovalsAreDifferences() {
		GITHUB.actionsAllowed = "all";
		GITHUB.defaultWorkflowPermissions = "write";
		GITHUB.workflowsApprovePullRequests = true;
		try {
			assertThat(this.repositories.organizationDifferences()).containsExactly(
					"GitHub Actions allow all actions instead of the organization's own only (local_only)",
					"the default workflow permissions are write instead of read",
					"workflows may approve pull requests (can_approve_pull_request_reviews=true) instead of never");
		}
		finally {
			GITHUB.actionsAllowed = "local_only";
			GITHUB.defaultWorkflowPermissions = "read";
			GITHUB.workflowsApprovePullRequests = false;
		}
	}

	@Test
	void theDailyCheckWantsThePlatformAppToHoldActionsWrite() {
		assertThat(AppExpectations.PLATFORM_PERMISSIONS).containsEntry("actions", "write");
		GITHUB.app = registration(AppExpectations.PLATFORM_PERMISSIONS);

		assertThat(this.check.check(App.PLATFORM)).isEmpty();

		Map<String, String> withoutActions = new HashMap<>(AppExpectations.PLATFORM_PERMISSIONS);
		withoutActions.remove("actions");
		GITHUB.app = registration(withoutActions);
		List<String> differences = this.check.check(App.PLATFORM);

		assertThat(differences).singleElement().asString().startsWith("permissions: ")
			.endsWith(" instead of " + new TreeMap<>(AppExpectations.PLATFORM_PERMISSIONS));
	}

	private static String registration(Map<String, String> permissions) {
		return GitHubHttp.JSON.writeValueAsString(Map.of("slug", "vibefix-dev-platform", "permissions", permissions, "events",
				List.copyOf(new java.util.TreeSet<>(Set.copyOf(AppExpectations.PLATFORM_EVENTS)))));
	}

}
