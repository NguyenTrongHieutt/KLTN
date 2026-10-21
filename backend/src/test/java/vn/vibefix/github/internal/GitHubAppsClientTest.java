package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubApps.Registration;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/** AD-10: installation tokens narrowed by repository IDs and permissions, revocation, and registrations. */
class GitHubAppsClientTest {

	@TempDir
	Path directory;

	private GitHubStub github;

	private GitHubAppsClient client;

	@BeforeEach
	void aConfiguredPlatformApp() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		Path key = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
		this.client = new GitHubAppsClient(new GitHubProperties(this.github.apiUrl(), this.github.gitUrl(), "vibefix-dev",
				new AppProperties(123456L, GitHubStub.INSTALLATION, key), null),
				Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC));
	}

	@AfterEach
	void stop() {
		this.github.close();
	}

	@Test
	void aTokenIsNarrowedToTheRepositoriesAndPermissionsOfItsOperation() {
		InstallationToken token = this.client.token(App.PLATFORM, List.of(1001L, 1002L), Map.of("contents", "write"));

		GitHubStub.Seen request = this.github.seen.getFirst();
		assertThat(request.path()).isEqualTo("/app/installations/7/access_tokens");
		assertThat(request.authorization()).startsWith("Bearer ey");
		assertThat(request.body().path("repository_ids").toString()).isEqualTo("[1001,1002]");
		assertThat(request.body().path("permissions").path("contents").asString()).isEqualTo("write");
		assertThat(token.repositoryIds()).containsExactlyInAnyOrder(1001L, 1002L);
		assertThat(token.toString()).as("a token never prints its value").doesNotContain(token.value());

		this.client.revoke(token);
		assertThat(this.github.revoked).hasValue(1);
		assertThat(this.github.seen.getLast().authorization()).isEqualTo("Bearer " + token.value());
	}

	@Test
	void anOrganizationTokenNamesNoRepository() {
		this.client.token(App.PLATFORM, List.of(), Map.of("organization_administration", "read"));

		assertThat(this.github.seen.getFirst().body().has("repository_ids")).isFalse();
	}

	@Test
	void theRegistrationIsReadWithTheAppsJwt() {
		Registration registration = this.client.registration(App.PLATFORM);

		assertThat(registration.slug()).isEqualTo("vibefix-dev-platform");
		assertThat(registration.permissions()).containsEntry("contents", "write").containsEntry("metadata", "read");
		assertThat(registration.events()).containsExactlyInAnyOrder("push", "pull_request");
	}

	@Test
	void anAppThatIsNotConfiguredIsRefusedByName() {
		assertThatThrownBy(() -> this.client.token(App.MERGE, List.of(), Map.of())).isInstanceOf(GitHubException.class)
			.hasMessageContaining("vibefix.github.merge-app.id");
	}

}
