package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories;

/**
 * Story 23.5b (FR-13, FR-20): the reads {@code product} makes of a Source Repository with a Source App token. The
 * contributors are unknown on a 404, so the guard fails closed, and only an empty repository (204) has none; the drift
 * poll reads whether the repository is archived and the commits after the copied one.
 */
class GitHubRepositoriesClientTest {

	private static final String TOKEN = "ghs_stubtoken_source";

	@TempDir
	Path directory;

	private GitHubStub github;

	private GitHubRepositories repositories;

	@BeforeEach
	void aStubGitHub() throws Exception {
		this.github = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.repositories = new GitHubRepositoriesClient(new GitHubProperties(this.github.apiUrl(),
				URI.create("https://github.com"), "vibefix-dev", null, null, null, null, null, null));
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
	void theContributorsAreReadPageByPageAndOnlyAnEmptyRepositoryHasNone() {
		this.github.contributors.put("lan/shop", List.of(11L, 12L));
		assertThat(this.repositories.contributorIds(TOKEN, "lan", "shop")).containsExactly(11L, 12L);
		assertThat(this.github.seen).filteredOn(request -> request.path().equals("/repos/lan/shop/contributors"))
			.singleElement()
			.satisfies(request -> assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN));

		this.github.contributors.put("lan/shop", LongStream.rangeClosed(1, 150).boxed().toList());
		assertThat(this.repositories.contributorIds(TOKEN, "lan", "shop")).as("page by page, 100 a page").hasSize(150);
		this.github.contributors.put("lan/shop", LongStream.rangeClosed(1, 600).boxed().toList());
		assertThat(this.repositories.contributorIds(TOKEN, "lan", "shop")).as("at most five pages")
			.hasSize(100 * GitHubRepositories.MAX_CONTRIBUTOR_PAGES);

		this.github.contributors.put("lan/empty", List.of());
		assertThat(this.repositories.contributorIds(TOKEN, "lan", "empty")).as("204: an empty repository").isEmpty();
	}

	@Test
	void aContributorReadGitHubRefusesOrCannotAnswerIsAnException() {
		assertThatThrownBy(() -> this.repositories.contributorIds(TOKEN, "lan", "hidden"))
			.as("404: a repository gone or hidden says nothing of who contributed")
			.isInstanceOfSatisfying(GitHubException.class, refused -> assertThat(refused.status()).isEqualTo(404));
		this.github.contributors.put("lan/shop", List.of(11L));
		this.github.unavailable.add("GET /repos/lan/shop/contributors");
		assertThatThrownBy(() -> this.repositories.contributorIds(TOKEN, "lan", "shop"))
			.isInstanceOfSatisfying(GitHubException.class, refused -> assertThat(refused.status()).isEqualTo(502));
	}

	@Test
	void theRepositoryShowsWhetherItIsArchivedAndTheCommitsSinceTheCopy() throws Exception {
		this.github.source("lan", "shop");
		this.github.publish("lan", "shop", 4001, 77, false, true);
		String copied = this.github.head("lan", "shop", "main");
		assertThat(this.repositories.read(TOKEN, "lan", "shop").orElseThrow().archived()).isFalse();
		assertThat(this.repositories.commitsSince(TOKEN, "lan/shop", copied, "main")).isEmpty();

		String first = this.github.push("lan", "shop", "main", "app.js", "console.log(1)\n");
		String second = this.github.push("lan", "shop", "main", "app.js", "console.log(2)\n");
		this.github.archive(4001);

		assertThat(this.repositories.read(TOKEN, "lan", "shop").orElseThrow().archived()).isTrue();
		assertThat(this.repositories.commitsSince(TOKEN, "lan/shop", copied, "main")).as("oldest first")
			.containsExactly(first, second);
		assertThat(this.repositories.commitsSince(TOKEN, "lan/shop", "0".repeat(40), "main"))
			.as("the copied commit gone from the branch's history").containsExactly("unknown");
		this.github.unavailable.add("GET /repos/lan/shop/compare/");
		assertThatThrownBy(() -> this.repositories.commitsSince(TOKEN, "lan/shop", copied, "main"))
			.isInstanceOf(GitHubException.class);
	}

	/**
	 * Story 23.6a: J7's head read is the branch's ref alone, with the caller's token, a branch with a slash read as a
	 * nested ref; no such branch is a 404, and GitHub down an exception.
	 */
	@Test
	void theHeadIsTheBranchRefReadWithTheCallersToken() throws Exception {
		this.github.source("lan", "quan");
		String head = this.github.head("lan", "quan", "main");
		try (org.eclipse.jgit.lib.Repository repository = this.github.repository("lan", "quan")) {
			var create = repository.updateRef("refs/heads/release/v1");
			create.setNewObjectId(repository.resolve("refs/heads/main"));
			create.update();
		}
		String release = this.github.push("lan", "quan", "release/v1", "notes.txt", "v1\n");

		assertThat(this.repositories.head(TOKEN, "lan/quan", "main")).isEqualTo(head);
		assertThat(this.repositories.head(TOKEN, "lan/quan", "release/v1")).isEqualTo(release);
		assertThat(this.github.seen).filteredOn(request -> request.path().startsWith("/repos/lan/quan/git/ref/heads/"))
			.extracting(GitHubStub.Seen::path, GitHubStub.Seen::authorization)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("/repos/lan/quan/git/ref/heads/main", "Bearer " + TOKEN),
					org.assertj.core.groups.Tuple.tuple("/repos/lan/quan/git/ref/heads/release/v1", "Bearer " + TOKEN));
		assertThatThrownBy(() -> this.repositories.head(TOKEN, "lan/quan", "gone"))
			.isInstanceOfSatisfying(GitHubException.class, refused -> assertThat(refused.status()).isEqualTo(404));
		this.github.unavailable.add("GET /repos/lan/quan/git/ref/");
		assertThatThrownBy(() -> this.repositories.head(TOKEN, "lan/quan", "main"))
			.isInstanceOfSatisfying(GitHubException.class, refused -> assertThat(refused.status()).isEqualTo(502));
	}

}
