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
		assertThat(AppExpectations.differences(App.MERGE,
				new Registration("vibefix-dev-merge", AppExpectations.MERGE_PERMISSIONS, Set.of())))
			.isEmpty();
		assertThat(AppExpectations.differences(App.MERGE,
				new Registration("vibefix-dev-merge", AppExpectations.MERGE_PERMISSIONS, Set.of("installation_target"))))
			.isEmpty();
	}

	@Test
	void aMissingEventOrAnExtraPermissionIsADifference() {
		Set<String> noPush = new HashSet<>(AppExpectations.PLATFORM_EVENTS);
		noPush.remove("push");
		Map<String, String> issues = new HashMap<>(AppExpectations.MERGE_PERMISSIONS);
		issues.put("issues", "write");

		assertThat(AppExpectations.differences(App.PLATFORM, platform(noPush))).singleElement()
			.asString()
			.startsWith("events: ")
			.doesNotContain("permissions");
		assertThat(AppExpectations.differences(App.MERGE, new Registration("vibefix-dev-merge", issues, Set.of("push"))))
			.hasSize(2)
			.anySatisfy(difference -> assertThat(difference).startsWith("permissions: ").contains("issues=write"))
			.anySatisfy(difference -> assertThat(difference).isEqualTo("events: [push] instead of []"));
	}

	@Test
	void withARunnerThePlatformAppHoldsActionsWriteAndOnlyThen() {
		Map<String, String> withActions = new HashMap<>(AppExpectations.PLATFORM_PERMISSIONS);
		withActions.put("actions", "write");
		Registration dispatching = new Registration("vibefix-dev-platform", withActions, AppExpectations.PLATFORM_EVENTS);

		assertThat(AppExpectations.permissions(App.PLATFORM, true)).isEqualTo(withActions);
		assertThat(AppExpectations.permissions(App.PLATFORM, false)).isEqualTo(AppExpectations.PLATFORM_PERMISSIONS);
		assertThat(AppExpectations.permissions(App.MERGE, true)).as("the Merge App never dispatches")
			.isEqualTo(AppExpectations.MERGE_PERMISSIONS);
		assertThat(AppExpectations.differences(App.PLATFORM, dispatching, true)).isEmpty();
		assertThat(AppExpectations.differences(App.PLATFORM, platform(AppExpectations.PLATFORM_EVENTS), true)).singleElement()
			.asString()
			.startsWith("permissions: ")
			.endsWith("instead of " + new TreeMap<>(withActions));
		assertThat(AppExpectations.differences(App.PLATFORM, dispatching)).as("without a runner, Actions write is a difference")
			.singleElement()
			.asString()
			.contains("actions=write");
		assertThat(AppExpectations.differences(App.MERGE,
				new Registration("vibefix-dev-merge", AppExpectations.MERGE_PERMISSIONS, Set.of()), true))
			.isEmpty();
	}

	private static Registration platform(Set<String> events) {
		return new Registration("vibefix-dev-platform", AppExpectations.PLATFORM_PERMISSIONS, events);
	}

}
