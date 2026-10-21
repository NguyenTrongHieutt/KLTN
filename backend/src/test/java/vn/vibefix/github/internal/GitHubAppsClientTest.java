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
import java.util.TreeMap;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubApps.Registration;
import vn.vibefix.github.GitHubApps.Unnarrowed;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * AD-10: installation tokens narrowed by 1 to 500 repository IDs and the permissions of their operation,
 * the three unnarrowed purposes, revocation after use, and registrations.
 */
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
	void aNarrowedTokenNamesOneTo500Repositories() {
		List<Long> tooMany = LongStream.rangeClosed(1, GitHubApps.MAX_REPOSITORIES + 1).boxed().toList();

		assertThatThrownBy(() -> this.client.token(App.PLATFORM, List.of(), Map.of("contents", "read")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unnarrowed");
		assertThatThrownBy(() -> this.client.token(App.PLATFORM, tooMany, Map.of("contents", "read")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(this.github.seen).as("nothing was minted").isEmpty();

		this.client.token(App.PLATFORM, tooMany.subList(0, GitHubApps.MAX_REPOSITORIES), Map.of("contents", "read"));
		assertThat(this.github.seen.getFirst().body().path("repository_ids").size()).isEqualTo(GitHubApps.MAX_REPOSITORIES);
	}

	@Test
	void anUnnarrowedTokenNamesNoRepositoryAndOnlyItsPurposesPermissions() {
		this.client.unnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ);
		this.client.unnarrowed(App.PLATFORM, Unnarrowed.REPOSITORY_CREATION);
		this.client.unnarrowed(App.PLATFORM, Unnarrowed.FORK);

		assertThat(this.github.seen).allSatisfy(request -> assertThat(request.body().has("repository_ids")).isFalse());
		assertThat(this.github.seen).extracting(request -> permissions(request.body().path("permissions")))
			.containsExactly(Map.of("organization_administration", "read", "members", "read"),
					Map.of("administration", "write"), Map.of("administration", "write", "contents", "read"));
	}

	@Test
	void aTokenIsRevokedAfterItsCallEvenWhenTheCallFails() {
		String value = this.client.withToken(App.PLATFORM, List.of(1001L), Map.of("contents", "read"),
				InstallationToken::value);

		assertThat(this.github.revoked).hasValue(1);
		assertThat(this.github.seen.getLast().authorization()).isEqualTo("Bearer " + value);

		assertThatThrownBy(() -> this.client.withUnnarrowed(App.PLATFORM, Unnarrowed.ORGANIZATION_READ, token -> {
			throw new IllegalStateException("the call failed");
		})).hasMessage("the call failed");
		assertThat(this.github.revoked).hasValue(2);
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
		assertThatThrownBy(() -> this.client.token(App.MERGE, List.of(1001L), Map.of())).isInstanceOf(GitHubException.class)
			.hasMessageContaining("vibefix.github.merge-app.id");
		assertThat(this.client.isConfigured(App.MERGE)).isFalse();
		assertThat(this.client.isConfigured(App.PLATFORM)).isTrue();
	}

	private static Map<String, String> permissions(JsonNode permissions) {
		Map<String, String> map = new TreeMap<>();
		permissions.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asString()));
		return map;
	}

}
