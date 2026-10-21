package vn.vibefix.github.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jgit.api.ArchiveCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE} (FR-21, FR-38, AD-32): the ZIP of a platform repository's branch, built in the sandbox
 * with JGit's {@link ArchiveCommand} from tree objects, so nothing is checked out and no path is
 * resolved on disk. Its ZIP format stores a symlink as an entry holding the link's text, never what it
 * points at. Like every handler, a plain class that only the sandbox worker (or the in-process stand-in)
 * creates.
 * <p>
 * Input: {@code repository} ({@code owner/name}) and {@code branch}. Output: {@code zip} (Base64),
 * {@code sha} (the commit archived), and {@code size}; or {@code refused}: {@value #BRANCH_NOT_FOUND} or
 * {@value #TOO_LARGE}.
 */
final class RepositoryZipJobHandler implements SandboxJobHandler {

	static final String TYPE = "REPOSITORY_ZIP";

	static final String BRANCH_NOT_FOUND = "BRANCH_NOT_FOUND";

	static final String TOO_LARGE = "TOO_LARGE";

	/** The ZIP is refused above this size; AD-13 refuses Source Repositories above 250 MB. */
	static final long MAX_BYTES = 300L * 1024 * 1024;

	private static final String FORMAT = "vibefix-zip";

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,255}");

	static {
		ArchiveCommand.registerFormat(FORMAT, new StoredLinkZip());
	}

	private final String gitUrl;

	RepositoryZipJobHandler(Environment environment) {
		String url = environment.getProperty("vibefix.github.git-url", "https://github.com");
		this.gitUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public JsonNode handle(JsonNode input) {
		String repository = input.path("repository").asString("");
		String branch = input.path("branch").asString("");
		if (!REPOSITORY.matcher(repository).matches() || repository.endsWith("/.") || repository.endsWith("/..")
				|| !BRANCH.matcher(branch).matches() || branch.contains("..") || branch.startsWith("/")) {
			throw new IllegalArgumentException("repository must be owner/name and branch a branch name");
		}
		String url = this.gitUrl + "/" + repository + ".git";
		try {
			if (Git.lsRemoteRepository().setRemote(url).setHeads(true).setTimeout(60).call()
				.stream()
				.noneMatch(ref -> ref.getName().equals("refs/heads/" + branch))) {
				return refused(BRANCH_NOT_FOUND);
			}
		}
		catch (GitAPIException ex) {
			throw new IllegalStateException("The branches of " + repository + " were not read: " + ex.getClass().getSimpleName(),
					ex);
		}
		Path directory;
		try {
			directory = Files.createTempDirectory("vibefix-zip-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		ObjectChecker checker = new ObjectChecker().setSafeForWindows(true).setSafeForMacOS(true);
		try (Git git = Git.cloneRepository()
			.setURI(url)
			.setDirectory(directory.toFile())
			.setBare(true)
			.setNoCheckout(true)
			.setBranchesToClone(List.of("refs/heads/" + branch))
			.setBranch("refs/heads/" + branch)
			.setCloneSubmodules(false)
			.setTimeout(600)
			.setTransportConfigCallback(transport -> {
				transport.setCheckFetchedObjects(true);
				transport.setObjectChecker(checker);
			})
			.call()) {
			ObjectId head = git.getRepository().resolve("refs/heads/" + branch);
			if (head == null) {
				return refused(BRANCH_NOT_FOUND);
			}
			RevCommit commit;
			try (RevWalk walk = new RevWalk(git.getRepository())) {
				commit = walk.parseCommit(head);
			}
			CappedBytes zip = new CappedBytes();
			git.archive().setTree(commit.getTree()).setFormat(FORMAT).setOutputStream(zip).call();
			if (zip.exceeded()) {
				return refused(TOO_LARGE);
			}
			ObjectNode output = JsonNodeFactory.instance.objectNode();
			output.put("zip", Base64.getEncoder().encodeToString(zip.toByteArray()));
			output.put("sha", commit.name());
			output.put("size", zip.size());
			return output;
		}
		catch (GitAPIException | IOException ex) {
			throw new IllegalStateException("The ZIP of " + repository + " failed: " + ex.getClass().getSimpleName(), ex);
		}
		finally {
			delete(directory);
		}
	}

	/** A ZIP that stores files and directories as they are in the tree, and a symlink as its link text. */
	static final class StoredLinkZip implements ArchiveCommand.Format<ZipOutputStream> {

		@Override
		public ZipOutputStream createArchiveOutputStream(OutputStream stream) {
			return new ZipOutputStream(stream, StandardCharsets.UTF_8);
		}

		@Override
		public ZipOutputStream createArchiveOutputStream(OutputStream stream, Map<String, Object> options) {
			return createArchiveOutputStream(stream);
		}

		@Override
		public void putEntry(ZipOutputStream out, ObjectId tree, String path, FileMode mode, ObjectLoader loader)
				throws IOException {
			if (path.startsWith("/") || List.of(path.split("/")).contains("..")) {
				throw new IOException("Unsafe path in the tree");
			}
			if (mode == FileMode.TREE) {
				out.putNextEntry(new ZipEntry(path.endsWith("/") ? path : path + "/"));
				out.closeEntry();
				return;
			}
			if (mode == FileMode.GITLINK) {
				// AD-32: no submodules. ArchiveCommand passes one as an empty directory already; this is a backstop.
				return;
			}
			ZipEntry entry = new ZipEntry(path);
			if (mode == FileMode.SYMLINK) {
				// The link's text, stored as a file; it is never followed.
				entry.setComment("symlink");
			}
			out.putNextEntry(entry);
			if (loader != null) {
				loader.copyTo(out);
			}
			out.closeEntry();
		}

		@Override
		public Iterable<String> suffixes() {
			return List.of(".vibefix.zip");
		}

	}

	/** Keeps the ZIP in memory, noting when it grows past {@link #MAX_BYTES}. */
	private static final class CappedBytes extends ByteArrayOutputStream {

		private boolean exceeded;

		@Override
		public synchronized void write(byte[] bytes, int offset, int length) {
			if (count() + length > MAX_BYTES) {
				this.exceeded = true;
				return;
			}
			super.write(bytes, offset, length);
		}

		@Override
		public synchronized void write(int value) {
			if (count() + 1 > MAX_BYTES) {
				this.exceeded = true;
				return;
			}
			super.write(value);
		}

		private long count() {
			return this.count;
		}

		boolean exceeded() {
			return this.exceeded;
		}

	}

	private static JsonNode refused(String code) {
		ObjectNode output = JsonNodeFactory.instance.objectNode();
		output.put("refused", code);
		return output;
	}

	private static void delete(Path directory) {
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException ex) {
			// A leftover temporary directory holds a platform repository's objects and nothing else.
		}
	}

}
