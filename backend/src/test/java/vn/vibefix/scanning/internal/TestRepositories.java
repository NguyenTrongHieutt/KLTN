package vn.vibefix.scanning.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

/**
 * Builds repositories for scan tests at {@code root/owner/name.git}, where the scan job clones
 * {@code file:} URLs from. Commits carry the times they are given, so the walk's oldest-first order
 * is the order they were made in.
 */
final class TestRepositories {

	private TestRepositories() {
	}

	/** {@code root} as the job's {@code vibefix.github.git-url}. */
	static String gitUrl(Path root) {
		return root.toUri().toString();
	}

	static Git create(Path root, String fullName) {
		try {
			return Git.init().setDirectory(root.resolve(fullName + ".git").toFile()).setInitialBranch("main").call();
		}
		catch (GitAPIException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Writes {@code files}, deletes {@code deleted}, and commits at {@code when}. */
	static RevCommit commit(Git git, Instant when, Map<String, String> files, Set<String> deleted) {
		Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
		files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
		return commitBytes(git, when, bytes, deleted);
	}

	/** As {@link #commit}, with each file's bytes: Story 16.3's spreadsheets and databases. */
	static RevCommit commitBytes(Git git, Instant when, Map<String, byte[]> files, Set<String> deleted) {
		try {
			Path work = git.getRepository().getWorkTree().toPath();
			for (Map.Entry<String, byte[]> file : files.entrySet()) {
				Path path = work.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.write(path, file.getValue());
				git.add().addFilepattern(file.getKey()).call();
			}
			for (String path : deleted) {
				git.rm().addFilepattern(path).call();
			}
			PersonIdent lan = new PersonIdent("Lan", "lan@example.com", when, ZoneOffset.ofHours(7));
			return git.commit().setMessage("Change at " + when).setAuthor(lan).setCommitter(lan).setAllowEmpty(true).call();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (GitAPIException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A commit on {@code main} whose root tree holds exactly {@code entries} (name to mode and
	 * content), written below the porcelain so names and modes no checkout would allow are possible.
	 */
	static ObjectId rawCommit(Repository repository, Map<String, Map.Entry<FileMode, String>> entries) {
		try (ObjectInserter inserter = repository.newObjectInserter()) {
			TreeFormatter tree = new TreeFormatter();
			entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
				try {
					ObjectId blob = inserter.insert(Constants.OBJ_BLOB,
							entry.getValue().getValue().getBytes(StandardCharsets.UTF_8));
					tree.append(entry.getKey(), entry.getValue().getKey(), blob);
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			});
			CommitBuilder commit = new CommitBuilder();
			PersonIdent lan = new PersonIdent("Lan", "lan@example.com", Instant.parse("2026-09-26T03:00:00Z"),
					ZoneOffset.ofHours(7));
			commit.setTreeId(inserter.insert(tree));
			commit.setAuthor(lan);
			commit.setCommitter(lan);
			commit.setMessage("Raw tree");
			ObjectId id = inserter.insert(commit);
			inserter.flush();
			RefUpdate update = repository.updateRef("refs/heads/main");
			update.setNewObjectId(id);
			update.setForceUpdate(true);
			update.update();
			return id;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Lets Windows delete the repositories: JGit keeps pack files open in its caches. */
	static void releaseFiles() {
		RepositoryCache.clear();
		new WindowCacheConfig().install();
	}

}
