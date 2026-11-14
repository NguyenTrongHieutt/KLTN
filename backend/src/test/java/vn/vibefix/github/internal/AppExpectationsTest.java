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
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, Set.of())))
			.isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, Set.of("installation_target"))))
			.isEmpty();
	}

	/**
	 * The two kinds of difference, on the App that has both permissions and events; the Source App's own pair is in
	 * {@link #theSourceAppReadsContentsAndMetadataDispatchesActionsAndHasNoWebhook}.
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

	/** 2026-11-12 (AD-10, AD-12, R9): the Platform App no longer subscribes to push or repository_ruleset. */
	@Test
	void thePlatformAppSubscribesToNeitherPushNorRepositoryRulesets() {
		assertThat(AppExpectations.PLATFORM_EVENTS).containsExactlyInAnyOrder("pull_request", "repository", "member",
				"membership", "organization");
		Set<String> withPush = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		withPush.add("push");
		withPush.add("repository_ruleset");

		assertThat(AppExpectations.differences(App.PLATFORM, platform(withPush))).singleElement()
			.asString()
			.isEqualTo("events: [member, membership, organization, pull_request, push, repository, repository_ruleset]"
					+ " instead of [member, membership, organization, pull_request, repository]");
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

	/** Story 23.5: the Source App reads the code and metadata, dispatches vibefix.yml, and has no event or webhook. */
	@Test
	void theSourceAppReadsContentsAndMetadataDispatchesActionsAndHasNoWebhook() {
		Map<String, String> withWrite = new HashMap<>(AppExpectations.SOURCE_PERMISSIONS);
		withWrite.put("contents", "write");

		assertThat(AppExpectations.permissions(App.SOURCE)).isEqualTo(Map.of("contents", "read", "metadata", "read",
				"actions", "write"));
		assertThat(AppExpectations.webhook(App.SOURCE)).isFalse();
		assertThat(AppExpectations.webhook(App.PLATFORM)).as("the only App with a webhook (Story 23.9b)").isTrue();
		assertThat(AppExpectations.differences(App.SOURCE,
				new Registration("vibefix-dev-source", AppExpectations.SOURCE_PERMISSIONS, Set.of("meta"))))
			.isEmpty();
		assertThat(AppExpectations.differences(App.SOURCE, new Registration("vibefix-dev-source", withWrite, Set.of("push"))))
			.hasSize(2)
			.anySatisfy(difference -> assertThat(difference).startsWith("permissions: ").contains("contents=write"))
			.anySatisfy(difference -> assertThat(difference).isEqualTo("events: [push] instead of []"));
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
