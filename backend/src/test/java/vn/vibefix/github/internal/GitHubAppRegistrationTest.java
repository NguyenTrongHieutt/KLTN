package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.Registration;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * SG-20: the dev GitHub Apps are registered exactly as AD-10 lists them ({@link AppExpectations}, which
 * the daily configuration check uses too). Tagged {@code vibefix-dev} and left out of the default build:
 * it reads the real registrations, so it runs only with {@code ./mvnw -B test -Pvibefix-dev} and the
 * Apps' IDs and key files in {@code VIBEFIX_DEV_PLATFORM_APP_ID}, {@code VIBEFIX_DEV_PLATFORM_APP_KEY_FILE},
 * {@code VIBEFIX_DEV_MERGE_APP_ID}, and {@code VIBEFIX_DEV_MERGE_APP_KEY_FILE} (deploy/README.md,
 * "Spike S4"). GitHub leaves the global events out of {@code GET /app} even when the App subscribes to
 * them, so they are accepted when they appear and the {@code meta} subscription is checked on the App's
 * settings page.
 */
@Tag("vibefix-dev")
class GitHubAppRegistrationTest {

	@Test
	void thePlatformAppHasExactlyAd10sPermissionsAndEvents() {
		Registration registration = client("PLATFORM").registration(App.PLATFORM);

		assertThat(AppExpectations.differences(App.PLATFORM, registration)).as("the Platform App against AD-10").isEmpty();
	}

	@Test
	void theMergeAppHasExactlyAd10sPermissionsAndNoEvents() {
		Registration registration = client("MERGE").registration(App.MERGE);

		assertThat(AppExpectations.differences(App.MERGE, registration)).as("the Merge App against AD-10").isEmpty();
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
