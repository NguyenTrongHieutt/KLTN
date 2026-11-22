package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.Registration;

/** AD-10's registrations as the daily check (SG-18) and the vibefix-dev suite (SG-20) compare them. */
class AppExpectationsTest {

	@Test
	void ad10sRegistrationsMatchWithOrWithoutTheGlobalEvents() {
		Set<String> withMeta = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		withMeta.add("meta");

		assertThat(AppExpectations.differences(App.PLATFORM, platform(AppExpectations.PLATFORM_EVENTS))).isEmpty();
		assertThat(AppExpectations.differences(App.PLATFORM, platform(withMeta))).isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, Set.of("workflow_run", "push",
						"repository"))))
			.isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE, new Registration("vibefix-dev-source",
				AppExpectations.SOURCE_PERMISSIONS, Set.of("workflow_run", "push", "repository", "installation_target"))))
			.isEmpty();
	}

	/**
	 * The two kinds of difference, on the Platform App; the Source App's own pair is in
	 * {@link #theSourceAppReadsContentsAndMetadataDispatchesActionsAndHearsOfItsRuns}.
	 */
	@Test
	void aMissingEventOrAnExtraPermissionIsADifference() {
		Set<String> noMember = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		noMember.remove("member");
		Map<String, String> issues = new HashMap<>(AppExpectations.PLATFORM_PERMISSIONS);
		issues.put("issues", "write");

		assertThat(AppExpectations.differences(App.PLATFORM, platform(noMember))).singleElement()
			.asString()
			.startsWith("events: ")
			.doesNotContain("permissions");
		assertThat(AppExpectations.differences(App.PLATFORM,
				new Registration("vibefix-dev-platform", issues, AppExpectations.PLATFORM_EVENTS)))
			.singleElement()
			.asString()
			.startsWith("permissions: ")
			.contains("issues=write");
	}

	/**
	 * 2026-11-12 (AD-10, AD-12, R9): the Platform App no longer subscribes to push or repository_ruleset. Story 24.5a:
	 * it hears of its runner's runs through workflow_run.
	 */
	@Test
	void thePlatformAppSubscribesToNeitherPushNorRepositoryRulesets() {
		assertThat(AppExpectations.PLATFORM_EVENTS).containsExactlyInAnyOrder("pull_request", "repository", "member",
				"membership", "organization", "workflow_run");
		Set<String> withPush = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		withPush.add("push");
		withPush.add("repository_ruleset");

		assertThat(AppExpectations.differences(App.PLATFORM, platform(withPush))).singleElement()
			.asString()
			.isEqualTo("events: [member, membership, organization, pull_request, push, repository, repository_ruleset,"
					+ " workflow_run] instead of [member, membership, organization, pull_request, repository, workflow_run]");
	}

	/** Story 24.5a: a Platform App that does not send workflow_run differs from AD-10. */
	@Test
	void thePlatformAppWithoutWorkflowRunDiffers() {
		Set<String> without = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		without.remove("workflow_run");

		assertThat(AppExpectations.differences(App.PLATFORM, platform(without))).singleElement()
			.asString()
			.isEqualTo("events: [member, membership, organization, pull_request, repository] instead of"
					+ " [member, membership, organization, pull_request, repository, workflow_run]");
	}

	/** The runner is required, so the Platform App always holds Actions write to dispatch it (AD-46). */
	@Test
	void thePlatformAppAlwaysHoldsActionsAndWorkflowsWrite() {
		Map<String, String> withoutActions = new HashMap<>(AppExpectations.PLATFORM_PERMISSIONS);
		withoutActions.remove("actions");

		assertThat(AppExpectations.permissions(App.PLATFORM)).containsEntry("actions", "write")
			.containsEntry("workflows", "write");
		assertThat(AppExpectations.differences(App.PLATFORM,
				new Registration("vibefix-dev-platform", withoutActions, AppExpectations.PLATFORM_EVENTS)))
			.singleElement()
			.asString()
			.startsWith("permissions: ")
			.endsWith("instead of " + new TreeMap<>(AppExpectations.PLATFORM_PERMISSIONS));
	}

	/**
	 * Story 23.5: the Source App reads the code and metadata and dispatches vibefix.yml. Story 24.5a: it has a webhook,
	 * as the Platform App has, and sends workflow_run. Story 24.6: push and repository too, for Source drift; its
	 * permissions stay as they were.
	 */
	@Test
	void theSourceAppReadsContentsAndMetadataDispatchesActionsAndHearsOfItsRunsAndItsDrift() {
		Map<String, String> withWrite = new HashMap<>(AppExpectations.SOURCE_PERMISSIONS);
		withWrite.put("contents", "write");

		assertThat(AppExpectations.permissions(App.SOURCE)).isEqualTo(Map.of("contents", "read", "metadata", "read",
				"actions", "write"));
		assertThat(AppExpectations.events(App.SOURCE)).containsExactlyInAnyOrder("workflow_run", "push", "repository");
		assertThat(AppExpectations.webhook(App.SOURCE)).isTrue();
		assertThat(AppExpectations.webhook(App.PLATFORM)).isTrue();
		assertThat(AppExpectations.differences(App.SOURCE, new Registration("vibefix-dev-source",
				AppExpectations.SOURCE_PERMISSIONS, Set.of("workflow_run", "push", "repository", "meta"))))
			.isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, Set.of())))
			.singleElement()
			.isEqualTo("events: [] instead of [push, repository, workflow_run]");
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", withWrite, Set.of("push", "pull_request"))))
			.hasSize(2)
			.anySatisfy(difference -> assertThat(difference).startsWith("permissions: ").contains("contents=write"))
			.anySatisfy(difference -> assertThat(difference)
				.isEqualTo("events: [pull_request, push] instead of [push, repository, workflow_run]"));
	}

	/**
	 * Story 24.6: the Source App before its drift events (workflow_run alone, as Story 24.5a left it) differs, so the
	 * daily check alerts until the author ticks Push and Repository.
	 */
	@Test
	void theSourceAppWithoutPushOrRepositoryDiffers() {
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, Set.of("workflow_run"))))
			.singleElement()
			.isEqualTo("events: [workflow_run] instead of [push, repository, workflow_run]");
		assertThat(AppExpectations.differences(App.SOURCE, new Registration("vibefix-dev-source",
				AppExpectations.SOURCE_PERMISSIONS, Set.of("workflow_run", "push"))))
			.singleElement()
			.isEqualTo("events: [push, workflow_run] instead of [push, repository, workflow_run]");
	}

	/**
	 * Story 24.6: GitHub sends installation and installation_repositories to every App, so the Source App matches AD-10
	 * whether {@code GET /app} lists them or not. The Platform App is unchanged: they are no event of its.
	 */
	@Test
	void theSourceAppMatchesWithOrWithoutTheInstallationEvents() {
		Set<String> listed = new HashSet<>(AppExpectations.SOURCE_EVENTS);
		listed.addAll(Set.of("installation", "installation_repositories"));
		Set<String> one = new HashSet<>(AppExpectations.SOURCE_EVENTS);
		one.add("installation_repositories");
		Set<String> platform = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		platform.add("installation");

		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, AppExpectations.SOURCE_EVENTS)))
			.isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, listed))).isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, one))).isEmpty();
		assertThat(AppExpectations.differences(App.PLATFORM, platform(platform))).singleElement()
			.asString()
			.startsWith("events: [installation, ");
	}

	/** Story 23.9b (R1, R16, AD-10): the Merge App is retired, so an environment has exactly these two Apps. */
	@Test
	void anEnvironmentHasExactlyThePlatformAppAndTheSourceApp() {
		assertThat(App.values()).containsExactly(App.PLATFORM, App.SOURCE);
	}

	private static Registration platform(Set<String> events) {
		return new Registration("vibefix-dev-platform", AppExpectations.PLATFORM_PERMISSIONS, events);
	}

}
