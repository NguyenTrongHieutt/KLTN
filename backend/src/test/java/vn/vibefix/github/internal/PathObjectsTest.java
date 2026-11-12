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

import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.PlatformRepositories.PathObjects;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * FR-32, AD-18: {@link GitHubPlatformRepositories#pathObjects} reads one path at two commits, and both
 * commits, in one GraphQL query whose text holds no value, with a token narrowed to the repository and
 * contents read. A commit GitHub does not know, a query answered with errors, and a refusal are
 * {@link GitHubException}s.
 */
class PathObjectsTest {

	private static final String WORKFLOWS = ".github/workflows";

	@TempDir
	Path directory;

	private GitHubStub github;

	private GitHubPlatformRepositories repositories;

	private PlatformRepository working;

	private String start;

	@BeforeEach
	void aWorkingRepositoryWithAWorkflowsFolder() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.github.source("lan-dev", "quan-may");
		long id = this.github.platformRepository("job-1-working", "lan-dev", "quan-may");
		this.working = new PlatformRepository(id, "job-1-working", GitHubStub.ORGANIZATION + "/job-1-working");
		this.start = this.github.head(GitHubStub.ORGANIZATION, "job-1-working", "main");
		Path key = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
		Clock clock = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);
		GitHubAppsClient apps = new GitHubAppsClient(new GitHubProperties(this.github.apiUrl(), this.github.gitUrl(),
				GitHubStub.ORGANIZATION, new AppProperties(123456L, GitHubStub.INSTALLATION, key), null), clock);
		this.repositories = new GitHubPlatformRepositories(apps, (type, input) -> {
			throw new UnsupportedOperationException("no sandbox job here: " + type);
		}, clock);
	}

	@AfterEach
	void stop() {
		this.github.close();
		// JGit keeps pack files open in its window cache; Windows cannot delete the temporary directory
		// until they are released.
		RepositoryCache.clear();
		WindowCache.reconfigure(new WindowCacheConfig());
	}

	@Test
	void theSameFolderAtBothCommitsIsReadInOneQueryThatCarriesItsValuesAsVariables() throws Exception {
		String head = push(Map.of("src/booking.js", "export const free = true;\n"), List.of());

		PathObjects objects = this.repositories.pathObjects(this.working, this.start, head, WORKFLOWS);

		assertThat(objects.base()).isNotNull().isEqualTo(objects.head());
		assertThat(objects.same()).isTrue();
		List<GitHubStub.Seen> queries = this.github.seen.stream().filter(request -> request.path().equals("/graphql")).toList();
		assertThat(queries).hasSize(1);
		GitHubStub.Seen query = queries.getFirst();
		assertThat(query.method()).isEqualTo("POST");
		assertThat(query.body().path("query").asString()).doesNotContain(this.start, head, "job-1-working",
				GitHubStub.ORGANIZATION, WORKFLOWS, "\"");
		assertThat(query.body().path("variables").path("owner").asString()).isEqualTo(GitHubStub.ORGANIZATION);
		assertThat(query.body().path("variables").path("name").asString()).isEqualTo("job-1-working");
		assertThat(query.body().path("variables").path("baseEntry").asString()).isEqualTo(this.start + ":" + WORKFLOWS);
		assertThat(query.body().path("variables").path("headEntry").asString()).isEqualTo(head + ":" + WORKFLOWS);
		var grant = this.github.grantOf(query.authorization().replaceFirst("^Bearer ", ""));
		assertThat(grant.path("repository_ids").toString()).isEqualTo("[" + this.working.id() + "]");
		assertThat(grant.path("permissions").toString()).isEqualTo("{\"contents\":\"read\"}");
		assertThat(this.github.revoked).as("the token is revoked after its call").hasValue(1);
	}

	@Test
	void aChangedFileUnderTheFolderGivesTheFolderAnotherObject() throws Exception {
		String head = push(Map.of(".github/workflows/ci.yml", "on: [push, pull_request]\n"), List.of());

		PathObjects objects = this.repositories.pathObjects(this.working, this.start, head, WORKFLOWS);

		assertThat(objects.base()).isNotNull();
		assertThat(objects.head()).isNotNull().isNotEqualTo(objects.base());
		assertThat(objects.same()).isFalse();
	}

	@Test
	void aFolderMissingAtOneSideIsAChangeAndMissingAtBothIsNone() throws Exception {
		String head = push(Map.of(), List.of(WORKFLOWS));

		PathObjects removed = this.repositories.pathObjects(this.working, this.start, head, WORKFLOWS);
		assertThat(removed.base()).isNotNull();
		assertThat(removed.head()).isNull();
		assertThat(removed.same()).isFalse();

		PathObjects added = this.repositories.pathObjects(this.working, head, this.start, WORKFLOWS);
		assertThat(added.base()).isNull();
		assertThat(added.head()).isNotNull();
		assertThat(added.same()).isFalse();

		String later = push(Map.of("src/booking.js", "export const free = true;\n"), List.of());
		PathObjects neither = this.repositories.pathObjects(this.working, head, later, WORKFLOWS);
		assertThat(neither.base()).isNull();
		assertThat(neither.head()).isNull();
		assertThat(neither.same()).isTrue();
	}

	@Test
	void aQueryAnsweredWithErrorsIsRefused() throws Exception {
		String head = push(Map.of("src/booking.js", "export const free = true;\n"), List.of());
		this.github.graphqlErrors.add("job-1-working");

		assertThatThrownBy(() -> this.repositories.pathObjects(this.working, this.start, head, WORKFLOWS))
			.isInstanceOf(GitHubException.class)
			.hasMessageContaining("Something went wrong");
	}

	@Test
	void anUnknownCommitOrRepositoryIsRefused() throws Exception {
		String unknown = "deadbeef".repeat(5);

		assertThatThrownBy(() -> this.repositories.pathObjects(this.working, this.start, unknown, WORKFLOWS))
			.isInstanceOf(GitHubException.class)
			.hasMessageContaining("does not know the headCommit");
		assertThatThrownBy(() -> this.repositories.pathObjects(this.working, unknown, this.start, WORKFLOWS))
			.isInstanceOf(GitHubException.class)
			.hasMessageContaining("does not know the baseCommit");
		PlatformRepository gone = new PlatformRepository(this.working.id(), "job-gone", GitHubStub.ORGANIZATION + "/job-gone");
		assertThatThrownBy(() -> this.repositories.pathObjects(gone, this.start, this.start, WORKFLOWS))
			.isInstanceOf(GitHubException.class)
			.hasMessageContaining("Could not resolve to a Repository");
	}

	@Test
	void anAnswerOtherThan200IsRefused() {
		this.github.unavailable.add("POST /graphql");

		assertThatThrownBy(() -> this.repositories.pathObjects(this.working, this.start, this.start, WORKFLOWS))
			.isInstanceOf(GitHubException.class)
			.satisfies(failed -> assertThat(((GitHubException) failed).status()).isEqualTo(502));
	}

	private String push(Map<String, String> files, List<String> removed) throws Exception {
		return this.github.push(GitHubStub.ORGANIZATION, "job-1-working", "main", files, removed, "Sửa lỗi", "hung@example.test");
	}

}
