package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

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

/**
 * Story 23.3 (D1): the trial merge fetches the private Intermediary with the read token it was given, so a job
 * without one, or with a blank one, is refused before anything is cloned, and no output carries the token. A
 * {@code file://} remote takes no credentials, so the narrowing itself is shown where the token is minted
 * ({@code PlatformCloneTokensTest}).
 */
class FinalMergeTrialJobHandlerTest {

	private static final String TOKEN = String.join("", "gh", "s_", "t".repeat(36));

	@TempDir
	Path directory;

	private GitHubStub git;

	private FinalMergeTrialJobHandler handler;

	private String head;

	@BeforeEach
	void aSourceAndItsIntermediary() throws Exception {
		this.git = new GitHubStub(Files.createDirectories(this.directory.resolve("git")));
		this.git.source("lan-dev", "quan-may");
		this.git.platformRepository("job-intermediary", "lan-dev", "quan-may");
		this.head = this.git.head("vibefix-dev", "job-intermediary", "main");
		this.handler = new FinalMergeTrialJobHandler(new MockEnvironment().withProperty("vibefix.github.git-url",
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
	void theIntermediaryIsFetchedWithItsReadTokenAndNoOutputCarriesIt() {
		JsonNode output = this.handler.handle(input());

		assertThat(output.path("clean").asBoolean()).isTrue();
		assertThat(output.path("moved").asBoolean(false)).isFalse();
		assertThat(output.toString()).doesNotContain(TOKEN).doesNotContain("tttttttt");
	}

	@Test
	void aMissingOrBlankReadTokenIsRefused() {
		ObjectNode missing = input();
		missing.remove("intermediaryToken");
		ObjectNode blank = input().put("intermediaryToken", " ");

		assertThatThrownBy(() -> this.handler.handle(missing)).as("the Intermediary is private")
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining(TOKEN);
		assertThatThrownBy(() -> this.handler.handle(blank)).isInstanceOf(IllegalArgumentException.class);
	}

	private ObjectNode input() {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("source", "lan-dev/quan-may");
		input.put("sourceBranch", "main");
		input.put("intermediary", "vibefix-dev/job-intermediary");
		input.put("intermediaryBranch", "main");
		input.put("sha", this.head);
		input.put("intermediaryToken", TOKEN);
		return input;
	}

}
