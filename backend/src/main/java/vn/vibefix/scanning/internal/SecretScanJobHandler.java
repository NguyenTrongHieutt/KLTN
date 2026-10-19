package vn.vibefix.scanning.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.scanning.internal.HistoryScanner.Found;
import vn.vibefix.scanning.internal.HistoryScanner.Report;
import vn.vibefix.scanning.internal.HistoryScanner.UnscannedFile;
import vn.vibefix.shared.SandboxJobHandler;

/**
 * {@value #TYPE} (AD-18, AD-32, SG-14): clones a public repository and scans its whole history with
 * {@link ScanRules}. The clone is bare, into a temporary directory deleted afterwards, with the
 * object checker on (so {@code ..}, {@code .git} and other unsafe names are refused) and no
 * submodules; JGit runs no hooks, filters, or LFS, and nothing is checked out. The fetch is cancelled
 * past {@link ScanLimits#transferBytes()} or the timeout. No AI is involved and nothing leaves the
 * sandbox but this job's output, which never holds a credential: each one is masked and hashed.
 * <p>
 * Input: {@code repository} ({@code owner/name}), cloned from {@code vibefix.github.git-url}, and
 * {@code limits} ({@link ScanLimits}). Output: {@code commits}, {@code scannedBlobs},
 * {@code unscannedCount}, {@code unscanned} (path, size), {@code stopped} (a cap's code, or none), and
 * {@code found} (rule, type, path, commit, date, masked value, hash); or {@code refused}:
 * {@value #TRANSFER_LIMIT}, {@value HistoryScanner#TIMEOUT}, or {@value #UNSAFE_REPOSITORY}. Like
 * every handler, a plain class that only the sandbox worker (or the in-process stand-in) creates.
 */
final class SecretScanJobHandler implements SandboxJobHandler {

	static final String TYPE = "SECRET_SCAN";

	static final String TRANSFER_LIMIT = "TRANSFER_LIMIT";

	static final String UNSAFE_REPOSITORY = "UNSAFE_REPOSITORY";

	private static final Logger log = LoggerFactory.getLogger(SecretScanJobHandler.class);

	private static final java.util.regex.Pattern REPOSITORY = java.util.regex.Pattern
		.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");

	private final String gitUrl;

	private final ScanRules rules;

	SecretScanJobHandler(Environment environment) {
		String url = environment.getProperty("vibefix.github.git-url", "https://github.com");
		this.gitUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		this.rules = ScanRules.load();
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public JsonNode handle(JsonNode input) {
		JsonNode repository = input.path("repository");
		if (!repository.isString() || !REPOSITORY.matcher(repository.stringValue()).matches()
				|| repository.stringValue().endsWith("/.") || repository.stringValue().endsWith("/..")) {
			throw new IllegalArgumentException("repository must be owner/name");
		}
		ScanLimits limits = ScanLimits.read(input.path("limits"));
		long deadline = System.nanoTime() + limits.timeout().toNanos();
		Path directory;
		try {
			directory = Files.createTempDirectory("vibefix-scan-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		try {
			CappedMonitor monitor = new CappedMonitor(directory, limits.transferBytes(), deadline);
			try (Git git = clone(this.gitUrl + "/" + repository.stringValue() + ".git", directory, limits, monitor)) {
				Report report = new HistoryScanner(new SecretMatcher(this.rules)).scan(git.getRepository(), limits,
						deadline);
				return output(report);
			}
			catch (GitAPIException | RuntimeException ex) {
				if (monitor.reason() != null) {
					return refused(monitor.reason());
				}
				if (causedByCorruptObject(ex)) {
					return refused(UNSAFE_REPOSITORY);
				}
				throw new IllegalStateException("The clone of " + repository.stringValue() + " failed", ex);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			delete(directory);
		}
	}

	private static Git clone(String url, Path directory, ScanLimits limits, ProgressMonitor monitor)
			throws GitAPIException {
		ObjectChecker checker = new ObjectChecker().setSafeForWindows(true).setSafeForMacOS(true);
		return Git.cloneRepository()
			.setURI(url)
			.setDirectory(directory.toFile())
			.setBare(true)
			.setNoCheckout(true)
			.setCloneAllBranches(true)
			.setCloneSubmodules(false)
			.setTimeout((int) Math.max(1, limits.timeout().toSeconds()))
			.setProgressMonitor(monitor)
			.setTransportConfigCallback(transport -> {
				transport.setCheckFetchedObjects(true);
				transport.setObjectChecker(checker);
			})
			.call();
	}

	private static boolean causedByCorruptObject(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof CorruptObjectException) {
				return true;
			}
		}
		return false;
	}

	static ObjectNode refused(String code) {
		ObjectNode output = JsonNodeFactory.instance.objectNode();
		output.put("refused", code);
		return output;
	}

	static ObjectNode output(Report report) {
		ObjectNode output = JsonNodeFactory.instance.objectNode();
		output.put("commits", report.commits());
		output.put("scannedBlobs", report.scannedBlobs());
		output.put("unscannedCount", report.unscannedCount());
		ArrayNode unscanned = output.putArray("unscanned");
		for (UnscannedFile file : report.unscanned()) {
			unscanned.addObject().put("path", file.path()).put("sizeBytes", file.sizeBytes());
		}
		if (report.stopped() != null) {
			output.put("stopped", report.stopped());
		}
		ArrayNode found = output.putArray("found");
		for (Found one : report.found()) {
			found.addObject()
				.put("ruleId", one.ruleId())
				.put("type", one.type())
				.put("path", one.path())
				.put("commit", one.commit())
				.put("committedAt", one.committedAt().toString())
				.put("masked", one.masked())
				.put("hash", one.hash());
		}
		return output;
	}

	private static void delete(Path directory) {
		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<>() {

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
					// Git writes packs read-only, which Windows refuses to delete.
					file.toFile().setWritable(true);
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException ex) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}

			});
		}
		catch (IOException ex) {
			// On Windows an open pack file cannot be deleted yet; the sandbox runs on Linux.
			log.warn("Could not delete the scan clone {}: {}", directory, ex.getClass().getSimpleName());
			directory.toFile().deleteOnExit();
		}
	}

	/** Cancels the fetch past the transfer cap, measured on the clone directory, or the deadline. */
	static final class CappedMonitor implements ProgressMonitor {

		private static final long MEASURE_EVERY_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

		private final Path directory;

		private final long transferBytes;

		private final long deadlineNanos;

		private final AtomicLong measuredAt = new AtomicLong();

		private volatile @Nullable String reason;

		CappedMonitor(Path directory, long transferBytes, long deadlineNanos) {
			this.directory = directory;
			this.transferBytes = transferBytes;
			this.deadlineNanos = deadlineNanos;
		}

		@Nullable String reason() {
			return this.reason;
		}

		@Override
		public boolean isCancelled() {
			if (this.reason != null) {
				return true;
			}
			long now = System.nanoTime();
			if (now > this.deadlineNanos) {
				this.reason = HistoryScanner.TIMEOUT;
				return true;
			}
			long last = this.measuredAt.get();
			if (now - last >= MEASURE_EVERY_NANOS && this.measuredAt.compareAndSet(last, now)
					&& size(this.directory) > this.transferBytes) {
				this.reason = TRANSFER_LIMIT;
				return true;
			}
			return false;
		}

		static long size(Path directory) {
			try (Stream<Path> files = Files.walk(directory)) {
				return files.filter(Files::isRegularFile).mapToLong(file -> {
					try {
						return Files.size(file);
					}
					catch (IOException ex) {
						return 0;
					}
				}).sum();
			}
			catch (IOException | UncheckedIOException ex) {
				return 0;
			}
		}

		@Override
		public void start(int totalTasks) {
		}

		@Override
		public void beginTask(String title, int totalWork) {
		}

		@Override
		public void update(int completed) {
		}

		@Override
		public void endTask() {
		}

		@Override
		public void showDuration(boolean enabled) {
		}

	}

}
