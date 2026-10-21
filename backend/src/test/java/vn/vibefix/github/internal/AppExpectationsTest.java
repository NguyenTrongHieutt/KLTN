package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

	private static Registration platform(Set<String> events) {
		return new Registration("vibefix-dev-platform", AppExpectations.PLATFORM_PERMISSIONS, events);
	}

}
