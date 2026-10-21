package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
 * FR-21, FR-38, AD-32: the final ZIP is built from tree objects. A symlink is stored as its link text and
 * never followed, so a link out of the repository leaks nothing; the commit archived is reported.
 */
class RepositoryZipJobHandlerTest {

	@TempDir
	Path directory;

	private RepositoryZipJobHandler handler;

	private String head;

	@BeforeEach
	void anIntermediaryRepositoryWithALink() throws Exception {
		Path root = Files.createDirectories(this.directory.resolve("git"));
		// The tree is written as objects, so the test needs no symlink support from the file system.
		try (Repository repository = new FileRepositoryBuilder()
			.setGitDir(root.resolve("vibefix-dev/job-intermediary.git").toFile())
			.setBare()
			.build()) {
			repository.create(true);
			try (ObjectInserter inserter = repository.newObjectInserter()) {
				TreeFormatter source = new TreeFormatter();
				source.append("app.js", FileMode.REGULAR_FILE, blob(inserter, "console.log('đặt bàn');\n"));
				TreeFormatter tree = new TreeFormatter();
				tree.append("README.md", FileMode.REGULAR_FILE, blob(inserter, "# Sổ đặt bàn\n"));
				tree.append("secrets", FileMode.SYMLINK, blob(inserter, "../../../etc/passwd"));
				tree.append("src", FileMode.TREE, inserter.insert(source));
				CommitBuilder commit = new CommitBuilder();
				commit.setTreeId(inserter.insert(tree));
				PersonIdent person = new PersonIdent("vibefix", "dev@vibefix.test");
				commit.setAuthor(person);
				commit.setCommitter(person);
				commit.setMessage("Bản cuối\n");
				ObjectId id = inserter.insert(commit);
				inserter.flush();
				RefUpdate update = repository.updateRef("refs/heads/main");
				update.setNewObjectId(id);
				update.forceUpdate();
				this.head = id.name();
			}
		}
		this.handler = new RepositoryZipJobHandler(new MockEnvironment().withProperty("vibefix.github.git-url",
				root.toUri().toString()));
	}

	@AfterEach
	void release() {
		// JGit keeps pack files open in its window cache; Windows cannot delete the temporary directory
		// until they are released.
		RepositoryCache.clear();
		WindowCache.reconfigure(new WindowCacheConfig());
	}

	@Test
	void theBranchIsZippedFromTreeObjectsWithALinkStoredAsItsText() throws Exception {
		JsonNode output = this.handler.handle(input("main"));

		assertThat(output.path("sha").asString()).isEqualTo(this.head);
		byte[] zip = Base64.getDecoder().decode(output.path("zip").asString());
		assertThat(output.path("size").asLong()).isEqualTo(zip.length);
		Map<String, String> entries = entries(zip);
		assertThat(entries).containsOnlyKeys("README.md", "secrets", "src/", "src/app.js");
		assertThat(entries.get("README.md")).isEqualTo("# Sổ đặt bàn\n");
		assertThat(entries.get("secrets")).as("the link's text, not what it points at").isEqualTo("../../../etc/passwd");
		assertThat(entries.get("src/app.js")).isEqualTo("console.log('đặt bàn');\n");
	}

	@Test
	void aMissingBranchIsRefused() {
		assertThat(this.handler.handle(input("release")).path("refused").asString())
			.isEqualTo(RepositoryZipJobHandler.BRANCH_NOT_FOUND);
	}

	@Test
	void unsafeInputIsRefused() {
		ObjectNode traversal = input("../main");
		ObjectNode outside = input("main").put("repository", "vibefix-dev/..");
		ObjectNode absolute = input("/main");

		assertThatThrownBy(() -> this.handler.handle(traversal)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.handler.handle(outside)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.handler.handle(absolute)).isInstanceOf(IllegalArgumentException.class);
	}

	private static ObjectId blob(ObjectInserter inserter, String content) throws Exception {
		return inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
	}

	private static ObjectNode input(String branch) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("repository", "vibefix-dev/job-intermediary");
		input.put("branch", branch);
		return input;
	}

	private static Map<String, String> entries(byte[] zip) throws Exception {
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return entries;
	}

}
