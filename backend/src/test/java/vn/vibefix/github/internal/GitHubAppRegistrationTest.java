package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.Registration;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * SG-20: the dev GitHub Apps are registered exactly as AD-10 lists them. Tagged {@code vibefix-dev}
 * and left out of the default build: it reads the real registrations, so it runs only with
 * {@code ./mvnw -B test -Pvibefix-dev} and the Apps' IDs and key files in
 * {@code VIBEFIX_DEV_PLATFORM_APP_ID}, {@code VIBEFIX_DEV_PLATFORM_APP_KEY_FILE},
 * {@code VIBEFIX_DEV_MERGE_APP_ID}, and {@code VIBEFIX_DEV_MERGE_APP_KEY_FILE} (deploy/README.md,
 * "Spike S4").
 */
@Tag("vibefix-dev")
class GitHubAppRegistrationTest {

	/** AD-10's Platform App: repository and organization permissions, and its events. */
	static final Map<String, String> PLATFORM_PERMISSIONS = Map.of("administration", "write", "contents", "write",
			"pull_requests", "write", "workflows", "write", "metadata", "read", "organization_administration", "read",
			"members", "read");

	/**
	 * AD-10's events as {@code GET /app} lists them. GitHub leaves out the global events
	 * ({@code installation_target}, {@code security_advisory}, {@code meta}) even when the App subscribes
	 * to them (Spike S4, 2026-10-21), so the {@code meta} subscription is checked on the App's settings page.
	 */
	static final Set<String> PLATFORM_EVENTS = Set.of("push", "pull_request", "repository", "member", "membership",
			"organization", "repository_ruleset");

	/** AD-10's Merge App: one Final Merge's permissions, and no event subscriptions. */
	static final Map<String, String> MERGE_PERMISSIONS = Map.of("contents", "write", "pull_requests", "write", "metadata",
			"read");

	@Test
	void thePlatformAppHasExactlyAd10sPermissionsAndEvents() {
		Registration registration = client("PLATFORM").registration(App.PLATFORM);

		assertThat(registration.permissions()).as("the Platform App's permissions").isEqualTo(PLATFORM_PERMISSIONS);
		assertThat(registration.events()).as("the Platform App's events").isEqualTo(PLATFORM_EVENTS);
	}

	@Test
	void theMergeAppHasExactlyAd10sPermissionsAndNoEvents() {
		Registration registration = client("MERGE").registration(App.MERGE);

		assertThat(registration.permissions()).as("the Merge App's permissions").isEqualTo(MERGE_PERMISSIONS);
		assertThat(registration.events()).as("the Merge App's events").isEmpty();
	}

	private static GitHubAppsClient client(String app) {
		String id = required("VIBEFIX_DEV_" + app + "_APP_ID");
		String keyFile = required("VIBEFIX_DEV_" + app + "_APP_KEY_FILE");
		AppProperties properties = new AppProperties(Long.parseLong(id), null, Path.of(keyFile));
		return new GitHubAppsClient(new GitHubProperties(URI.create("https://api.github.com"), null, "vibefix-dev",
				app.equals("PLATFORM") ? properties : null, app.equals("MERGE") ? properties : null), Clock.systemUTC());
	}

	private static String required(String name) {
		String value = System.getenv(name);
		assertThat(value).as(name + " names the dev App for the vibefix-dev suite").isNotBlank();
		return value;
	}

}
