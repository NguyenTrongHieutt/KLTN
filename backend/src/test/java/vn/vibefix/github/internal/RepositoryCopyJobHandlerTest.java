package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** AD-11, AD-32: the copy pushes exactly the approved commit, keeping its ID, and nothing else. */
class RepositoryCopyJobHandlerTest {

	@TempDir
	Path directory;

	private GitHubStub git;

	private RepositoryCopyJobHandler handler;

	private String head;

	@BeforeEach
	void aSourceAndAnEmptyTarget() throws Exception {
		this.git = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.git.source("lan-dev", "quan-may");
		Git.init().setBare(true).setInitialBranch("main")
			.setDirectory(this.directory.resolve("git/vibefix-dev/job-intermediary.git").toFile()).call().close();
		this.head = this.git.head("lan-dev", "quan-may", "main");
		this.handler = new RepositoryCopyJobHandler(new MockEnvironment().withProperty("vibefix.github.git-url",
				this.git.gitUrl().toString()));
	}

	@AfterEach
	void stop() {
		this.git.close();
		// JGit keeps pack files open in its window cache; Windows cannot delete the temporary directory
		// until they are released.
		RepositoryCache.clear();
		WindowCache.reconfigure(new WindowCacheConfig());
	}

	@Test
	void theApprovedCommitIsPushedWithItsId() throws Exception {
		JsonNode output = this.handler.handle(input(this.head));

		assertThat(output.path("pushed").asString()).isEqualTo(this.head);
		assertThat(this.git.head("vibefix-dev", "job-intermediary", "main")).isEqualTo(this.head);
		assertThat(this.git.hasFile("vibefix-dev", "job-intermediary", "main", ".github/workflows/ci.yml")).isTrue();
	}

	@Test
	void aHandoverCommitIsReadOnTheSourceBranchAndPinnedOnItsOwnBranch() throws Exception {
		JsonNode output = this.handler.handle(input(this.head).put("sourceBranch", "main").put("branch", "handover/h1"));

		assertThat(output.path("pushed").asString()).isEqualTo(this.head);
		assertThat(this.git.head("vibefix-dev", "job-intermediary", "handover/h1")).isEqualTo(this.head);
		assertThat(this.git.head("vibefix-dev", "job-intermediary", "main")).as("the default branch stays untouched").isNull();
		assertThatThrownBy(() -> this.handler.handle(input(this.head).put("sourceBranch", "../main")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aCommitTheBranchDoesNotHoldIsRefused() throws Exception {
		JsonNode output = this.handler.handle(input("0".repeat(40)));

		assertThat(output.path("refused").asString()).isEqualTo(RepositoryCopyJobHandler.SHA_NOT_FOUND);
		assertThat(this.git.head("vibefix-dev", "job-intermediary", "main")).isNull();
	}

	@Test
	void unsafeOrMissingInputIsRefused() {
		ObjectNode traversal = input(this.head).put("branch", "../main");
		ObjectNode noToken = input(this.head).put("token", "");
		ObjectNode badSha = input(this.head).put("sha", "HEAD");

		assertThatThrownBy(() -> this.handler.handle(traversal)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.handler.handle(noToken)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.handler.handle(badSha)).isInstanceOf(IllegalArgumentException.class);
	}

	private static ObjectNode input(String sha) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("source", "lan-dev/quan-may");
		input.put("target", "vibefix-dev/job-intermediary");
		input.put("sha", sha);
		input.put("branch", "main");
		// A fake token, built at run time: file:// remotes ignore it.
		input.put("token", String.join("", "gh", "s_", "x".repeat(36)));
		return input;
	}

}
