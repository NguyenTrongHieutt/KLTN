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
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE} (AD-11, AD-32): the Final Merge's trial merge. It bare-clones the public Source
 * Repository's default branch and fetches the public Intermediary Repository's branch, both read-only
 * with the object checker on and nothing checked out, and merges them in memory with JGit. Nothing is
 * written anywhere but the temporary directory, which is deleted. Like every handler, a plain class that
 * only the sandbox worker (or the in-process stand-in) creates.
 * <p>
 * Input: {@code source} and {@code intermediary} ({@code owner/name}), {@code sourceBranch},
 * {@code intermediaryBranch}, and {@code sha}, the Intermediary head the check read. Output:
 * {@code clean} and, for a conflict, {@code conflicts} (the paths); {@code moved} when the Intermediary
 * branch no longer has {@code sha} as its head.
 */
final class FinalMergeTrialJobHandler implements SandboxJobHandler {

	static final String TYPE = "FINAL_MERGE_TRIAL";

	private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,255}");

	private static final int TIMEOUT_SECONDS = 600;

	private static final String THEIRS = "refs/vibefix/intermediary";

	private final String gitUrl;

	FinalMergeTrialJobHandler(Environment environment) {
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
		String intermediary = valid(input, "intermediary", REPOSITORY);
		String sourceBranch = valid(input, "sourceBranch", BRANCH);
		String intermediaryBranch = valid(input, "intermediaryBranch", BRANCH);
		String sha = valid(input, "sha", SHA);
		for (String name : List.of(source, intermediary, sourceBranch, intermediaryBranch)) {
			if (name.contains("..") || name.startsWith("/") || name.endsWith("/.")) {
				throw new IllegalArgumentException("unsafe repository or branch name");
			}
		}
		Path directory;
		try {
			directory = Files.createTempDirectory("vibefix-trial-");
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
			.setBranchesToClone(List.of("refs/heads/" + sourceBranch))
			.setBranch("refs/heads/" + sourceBranch)
			.setCloneSubmodules(false)
			.setTimeout(TIMEOUT_SECONDS)
			.setTransportConfigCallback(transport -> {
				transport.setCheckFetchedObjects(true);
				transport.setObjectChecker(checker);
			})
			.call()) {
			git.fetch()
				.setRemote(this.gitUrl + "/" + intermediary + ".git")
				.setRefSpecs(new RefSpec("+refs/heads/" + intermediaryBranch + ":" + THEIRS))
				.setTimeout(TIMEOUT_SECONDS)
				.setCheckFetchedObjects(true)
				.call();
			ObjectId ours = git.getRepository().resolve("refs/heads/" + sourceBranch);
			ObjectId theirs = git.getRepository().resolve(THEIRS);
			ObjectNode output = JsonNodeFactory.instance.objectNode();
			if (theirs == null || !theirs.name().equals(sha) || ours == null) {
				output.put("clean", false);
				output.put("moved", true);
				return output;
			}
			ResolveMerger merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(git.getRepository(), true);
			boolean clean;
			try (RevWalk walk = new RevWalk(git.getRepository())) {
				clean = merger.merge(walk.parseCommit(ours), walk.parseCommit(theirs));
			}
			output.put("clean", clean);
			if (!clean) {
				ArrayNode conflicts = output.putArray("conflicts");
				merger.getUnmergedPaths().forEach(conflicts::add);
				if (merger.getFailingPaths() != null) {
					merger.getFailingPaths().keySet().forEach(conflicts::add);
				}
			}
			return output;
		}
		catch (GitAPIException | IOException ex) {
			throw new IllegalStateException("The trial merge of " + intermediary + " into " + source + " failed: "
					+ ex.getClass().getSimpleName(), ex);
		}
		finally {
			delete(directory);
		}
	}

	private static String valid(JsonNode input, String field, Pattern form) {
		JsonNode value = input.path(field);
		if (!value.isString() || !form.matcher(value.stringValue()).matches()) {
			throw new IllegalArgumentException(field + " is not valid");
		}
		return value.stringValue();
	}

	private static void delete(Path directory) {
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException ex) {
			// A leftover temporary directory holds two public repositories' objects and nothing else.
		}
	}

}
