package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE} (AD-11, AD-32): the copy into the Intermediary Repository. It bare-clones the public
 * Source Repository's default branch, with the object checker on, no submodules, and nothing checked
 * out, and pushes exactly the approved commit to the target's branch with the one token it was given,
 * never forced. The commit IDs are kept and no fork network is shared. Like every handler, a plain class
 * that only the sandbox worker (or the in-process stand-in) creates.
 * <p>
 * Input: {@code source} and {@code target} ({@code owner/name}), {@code sha}, {@code branch}, and
 * {@code token}. Output: {@code pushed} (the SHA), or {@code refused}: {@value #SHA_NOT_FOUND} when the
 * branch no longer holds the commit, or {@value #PUSH_REFUSED}.
 */
final class RepositoryCopyJobHandler implements SandboxJobHandler {

	static final String TYPE = "REPOSITORY_COPY";

	static final String SHA_NOT_FOUND = "SHA_NOT_FOUND";

	static final String PUSH_REFUSED = "PUSH_REFUSED";

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,255}");

	/** A clone or push that takes longer is given up; the operation tries again. */
	private static final int TIMEOUT_SECONDS = 600;

	private final String gitUrl;

	RepositoryCopyJobHandler(Environment environment) {
		String url = environment.getProperty("vibefix.github.git-url", "https://github.com");
		this.gitUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public JsonNode handle(JsonNode input) {
		String source = valid(input, "source", REPOSITORY);
		String target = valid(input, "target", REPOSITORY);
		String sha = valid(input, "sha", SHA);
		String branch = valid(input, "branch", BRANCH);
		if (branch.contains("..") || branch.startsWith("/") || source.endsWith("/.") || source.endsWith("/..")
				|| target.endsWith("/.") || target.endsWith("/..")) {
			throw new IllegalArgumentException("unsafe repository or branch name");
		}
		String token = input.path("token").asString("");
		if (token.isBlank()) {
			throw new IllegalArgumentException("token is required");
		}
		Path directory;
		try {
			directory = Files.createTempDirectory("vibefix-copy-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		ObjectChecker checker = new ObjectChecker().setSafeForWindows(true).setSafeForMacOS(true);
		try (Git git = Git.cloneRepository()
			.setURI(this.gitUrl + "/" + source + ".git")
			.setDirectory(directory.toFile())
			.setBare(true)
			.setNoCheckout(true)
			.setBranchesToClone(List.of("refs/heads/" + branch))
			.setBranch("refs/heads/" + branch)
			.setCloneSubmodules(false)
			.setTimeout(TIMEOUT_SECONDS)
			.setTransportConfigCallback(transport -> {
				transport.setCheckFetchedObjects(true);
				transport.setObjectChecker(checker);
			})
			.call()) {
			ObjectId commit = ObjectId.fromString(sha);
			if (!onBranch(git, branch, commit)) {
				return refused(SHA_NOT_FOUND);
			}
			Iterable<PushResult> results = git.push()
				.setRemote(this.gitUrl + "/" + target + ".git")
				.setRefSpecs(new RefSpec(sha + ":refs/heads/" + branch))
				.setForce(false)
				.setTimeout(TIMEOUT_SECONDS)
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
				.call();
			for (PushResult result : results) {
				for (RemoteRefUpdate update : result.getRemoteUpdates()) {
					if (update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
						return refused(PUSH_REFUSED);
					}
				}
			}
			ObjectNode output = JsonNodeFactory.instance.objectNode();
			output.put("pushed", sha);
			return output;
		}
		catch (GitAPIException ex) {
			// The message may name the remote, never the token, which only the credentials provider holds.
			throw new IllegalStateException("The copy of " + source + " into " + target + " failed: "
					+ ex.getClass().getSimpleName(), ex);
		}
		finally {
			delete(directory);
		}
	}

	/** Whether the cloned branch holds {@code commit} in its history. */
	private static boolean onBranch(Git git, String branch, ObjectId commit) {
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			ObjectId head = git.getRepository().resolve("refs/heads/" + branch);
			if (head == null || !git.getRepository().getObjectDatabase().has(commit)) {
				return false;
			}
			RevCommit wanted = walk.parseCommit(commit);
			return walk.isMergedInto(wanted, walk.parseCommit(head));
		}
		catch (IOException ex) {
			return false;
		}
	}

	private static String valid(JsonNode input, String field, Pattern form) {
		JsonNode value = input.path(field);
		if (!value.isString() || !form.matcher(value.stringValue()).matches()) {
			throw new IllegalArgumentException(field + " is not valid");
		}
		return value.stringValue();
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
			// A leftover temporary directory holds a public repository's objects and nothing else.
		}
	}

}
