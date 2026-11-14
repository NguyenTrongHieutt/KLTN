package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;
import vn.vibefix.shared.SandboxJobResult;

/**
 * Story 23.3 (D1, AD-10, deferred-work 659): the platform repositories are private. Every pull-request call holds
 * {@code contents} read besides its {@code pull_requests} permission, which the stub requires on a private
 * repository as GitHub does (Spike S6 Part C); the Working head is read with a token narrowed to it; and every
 * sandbox clone of a platform repository ({@code REPOSITORY_ZIP}, the only one left) gets a token narrowed to
 * that repository with {@code contents} read; the handover copy runs on the runner's Actions instead (AD-46, Story
 * 23.8a), Story 23.9a retired the trial merge with the Merge App's Final Merge, and Story 23.9b took that App's
 * own push with it. The stub's {@code file://} hosting takes no credentials, so these show the job receives the
 * narrowed token.
 */
class PlatformCloneTokensTest {

	@TempDir
	Path directory;

	private GitHubStub github;

	private GitHubAppsClient apps;

	private GitHubPlatformRepositories repositories;

	/** Each sandbox job sent, with its input. */
	private final List<JsonNode> jobs = Collections.synchronizedList(new ArrayList<>());

	private PlatformRepository intermediary;

	private PlatformRepository working;

	private String head;

	@BeforeEach
	void twoPrivateCopies() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.github.source("lan-dev", "quan-may");
		this.head = this.github.head("lan-dev", "quan-may", "main");
		long intermediaryId = this.github.platformRepository("job-1-intermediary", "lan-dev", "quan-may");
		long workingId = this.github.platformRepository("job-1-working", "lan-dev", "quan-may");
		this.intermediary = new PlatformRepository(intermediaryId, "job-1-intermediary", "vibefix-dev/job-1-intermediary");
		this.working = new PlatformRepository(workingId, "job-1-working", "vibefix-dev/job-1-working");
		Path key = Files.writeString(this.directory.resolve("github-platform-app-key"), TestKeys.pkcs1());
		Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);
		this.apps = new GitHubAppsClient(new GitHubProperties(this.github.apiUrl(), this.github.gitUrl(),
				GitHubStub.ORGANIZATION, new AppProperties(123456L, GitHubStub.INSTALLATION, key)), clock);
		vn.vibefix.shared.SandboxJobs sandbox = (type, input) -> {
			this.jobs.add(JsonNodeFactory.instance.objectNode().put("type", type).set("input", input.deepCopy()));
			return new SandboxJobResult(type, switch (type) {
				case RepositoryZipJobHandler.TYPE -> JsonNodeFactory.instance.objectNode().put("zip", "UEs=");
				default -> JsonNodeFactory.instance.objectNode().put("clean", true);
			});
		};
		this.repositories = new GitHubPlatformRepositories(this.apps, sandbox, clock);
	}

	@AfterEach
	void stop() {
		this.github.close();
		RepositoryCache.clear();
		WindowCache.reconfigure(new WindowCacheConfig());
	}

	@Test
	void theZipClonesWithAReadTokenNarrowedToTheRepository() {
		this.repositories.zip(this.intermediary, "main");

		assertThat(grant(onlyJob(RepositoryZipJobHandler.TYPE).path("token").asString()))
			.isEqualTo(narrowed(this.intermediary, Map.of("contents", "read")));
	}

	@Test
	void theWorkingHeadIsReadWithATokenNarrowedToIt() {
		assertThat(this.repositories.branchHead(this.working, "main")).isEqualTo(this.head);
		assertThat(this.github.seen).filteredOn(request -> request.path().endsWith("/access_tokens"))
			.last()
			.satisfies(request -> assertThat(request.body().path("repository_ids").toString())
				.isEqualTo("[" + this.working.id() + "]"));
	}

	@Test
	void everyPullRequestCallHoldsContentsReadWhichAPrivateRepositoryNeeds() throws Exception {
		try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
				this.directory.resolve("git/vibefix-dev/job-1-intermediary.git").toFile())) {
			org.eclipse.jgit.lib.RefUpdate update = git.getRepository().updateRef("refs/heads/handover/h1");
			update.setNewObjectId(git.getRepository().resolve("refs/heads/main"));
			update.update();
		}
		int number = this.repositories.openPullRequest(this.intermediary, "handover/h1", "main", "Bàn giao", "Nội dung");
		assertThat(this.repositories.openPullRequest(this.intermediary, "handover/h1", "main", "Bàn giao", "Nội dung"))
			.as("the head= lookup finds it").isEqualTo(number);
		this.repositories.commentOnPullRequest(this.intermediary, number, "Đóng");
		assertThat(this.repositories.pullRequestComments(this.intermediary, number)).containsExactly("Đóng");
		assertThat(this.repositories.pullRequest(this.intermediary, number).state()).isEqualTo("open");
		assertThat(this.repositories.openPullRequests(this.intermediary)).hasSize(1);
		assertThat(this.repositories.pullRequestMerged(this.intermediary, number)).isFalse();
		this.repositories.renameAndClosePullRequest(this.intermediary, number, "Bàn giao (lần 1)");
		this.repositories.closePullRequest(this.intermediary, number);

		assertThat(this.github.seen.stream()
			.filter(request -> request.path().endsWith("/access_tokens"))
			.filter(request -> request.body().path("permissions").has("pull_requests"))
			.map(request -> request.body().path("permissions").path("contents").asString("none")))
			.as("open, look up, comment, comments, read, list, merged, rename and close, close")
			.hasSize(9)
			.containsOnly("read");

		int refused = this.apps.withToken(App.PLATFORM, List.of(this.intermediary.id()), Map.of("pull_requests", "write"),
				token -> this.apps.http().send("POST", "/repos/vibefix-dev/job-1-intermediary/pulls", token.value(),
						Map.of("title", "x", "head", "handover/h1", "base", "main")).status());
		assertThat(refused).as("pull_requests alone, as before Story 23.3").isEqualTo(422);
	}

	private JsonNode onlyJob(String type) {
		assertThat(this.jobs).singleElement().satisfies(job -> assertThat(job.path("type").asString()).isEqualTo(type));
		return this.jobs.getFirst().path("input");
	}

	/** What the stub recorded for the token: its repositories and permissions. */
	private Map<String, Object> grant(String token) {
		assertThat(token).as("a token was sent").isNotBlank();
		JsonNode grant = this.github.grantOf(token);
		List<Long> ids = new ArrayList<>();
		grant.path("repository_ids").forEach(id -> ids.add(id.asLong()));
		Map<String, String> permissions = new TreeMap<>();
		grant.path("permissions").properties().forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
		return Map.of("repositories", ids, "permissions", permissions);
	}

	private static Map<String, Object> narrowed(PlatformRepository repository, Map<String, String> permissions) {
		return Map.of("repositories", List.of(repository.id()), "permissions", new TreeMap<>(permissions));
	}

}
