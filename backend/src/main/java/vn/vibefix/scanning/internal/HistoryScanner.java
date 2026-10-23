package vn.vibefix.scanning.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jspecify.annotations.Nullable;

import vn.vibefix.scanning.internal.PersonalDataMatcher.Hit;
import vn.vibefix.scanning.internal.ScanRules.PersonalKind;
import vn.vibefix.scanning.internal.SecretMatcher.Match;

/**
 * Walks every commit reachable from every ref of a bare repository, oldest first, and scans each
 * file version once for credentials and personal data, in the same run (AD-18, AD-32, FR-65).
 * Nothing is ever checked out: a file is read as a blob, a symbolic link as the text of its target,
 * which is never followed; submodule entries are skipped. A blob larger than
 * {@link ScanLimits#blobBytes()}, and a spreadsheet or database file whose extraction is off, is
 * listed, not read; a binary blob (a NUL byte in its first 8 000 bytes) is skipped. An {@code .xlsx}
 * workbook or a SQLite file is extracted to tables and given the Personal-Data Check as a data file
 * (Story 16.3), or listed {@value #UNREADABLE} when it cannot be read. The other caps stop the walk and
 * say which one was reached.
 */
final class HistoryScanner {

	/**
	 * A file the scan did not read.
	 * @param reason {@value #TOO_LARGE}, {@value #NOT_EXTRACTED}, or {@value #UNREADABLE}
	 */
	record UnscannedFile(String path, long sizeBytes, String reason) {
	}

	/**
	 * A credential, or a file of personal data, at the first commit the walk met it in.
	 * @param kinds the personal data found, empty for a credential
	 * @param lines the lines holding personal data, 0 for a credential
	 */
	record Found(String ruleId, String type, String path, String commit, Instant committedAt, String masked,
			String hash, Set<PersonalKind> kinds, int lines) {

		boolean personalData() {
			return !this.kinds.isEmpty();
		}

	}

	/**
	 * What the walk saw.
	 * @param stopped why the walk ended early, a cap's code or {@value #UNSAFE_PATH}; {@code null} when complete
	 */
	record Report(List<Found> found, int commits, int scannedBlobs, int unscannedCount, List<UnscannedFile> unscanned,
			@Nullable String stopped) {
	}

	static final String COMMIT_LIMIT = "COMMIT_LIMIT";

	static final String BLOB_LIMIT = "BLOB_LIMIT";

	static final String DECOMPRESSED_LIMIT = "DECOMPRESSED_LIMIT";

	static final String MATCH_LIMIT = "MATCH_LIMIT";

	static final String TIMEOUT = "TIMEOUT";

	/** A tree entry whose path could escape a checkout; the object checker refuses these at clone. */
	static final String UNSAFE_PATH = "UNSAFE_PATH";

	/** Larger than the size limit. */
	static final String TOO_LARGE = "TOO_LARGE";

	/** A spreadsheet or database file: its extraction is a separate step, off (FR-65 Tier 3). */
	static final String NOT_EXTRACTED = "NOT_EXTRACTED";

	/** A spreadsheet or database file the extraction could not read: broken, another format, or past a cap. */
	static final String UNREADABLE = "UNREADABLE";

	/** How many times the per-file cap an {@code .xlsx} may inflate to, a ZIP bomb guard. */
	static final int INFLATE_FACTOR = 8;

	/** The unscanned files listed by path; the rest are counted. */
	static final int LISTED_UNSCANNED = 50;

	/** The distinct credentials reported; a repository with more is not one a Customer fixes by hand. */
	static final int MAX_FOUND = 500;

	private static final int BINARY_PROBE = 8000;

	private final SecretMatcher matcher;

	private final PersonalDataMatcher personalData;

	private final java.util.function.Predicate<String> notExtracted;

	private final java.util.function.Predicate<String> extracted;

	HistoryScanner(ScanRules rules) {
		this.matcher = new SecretMatcher(rules);
		this.personalData = new PersonalDataMatcher(rules.personalData());
		this.notExtracted = path -> rules.personalData().notExtracted().matcher(path).find();
		this.extracted = path -> rules.personalData().extracted().matcher(path).find();
	}

	Report scan(Repository repository, ScanLimits limits, long deadlineNanos) throws IOException {
		List<Found> found = new ArrayList<>();
		Set<String> hashes = new HashSet<>();
		Set<ObjectId> seen = new HashSet<>();
		List<UnscannedFile> unscanned = new ArrayList<>();
		int commits = 0;
		int scanned = 0;
		int unscannedCount = 0;
		long decompressed = 0;
		String stopped = null;
		try (RevWalk walk = new RevWalk(repository); ObjectReader reader = repository.newObjectReader()) {
			walk.sort(RevSort.COMMIT_TIME_DESC);
			walk.sort(RevSort.REVERSE, true);
			for (Ref ref : repository.getRefDatabase().getRefs()) {
				if (ref.getObjectId() == null) {
					continue;
				}
				RevObject target = walk.peel(walk.parseAny(ref.getObjectId()));
				if (target instanceof RevCommit commit) {
					walk.markStart(commit);
				}
			}
			commits:
			for (RevCommit commit : walk) {
				if (++commits > limits.commits()) {
					stopped = COMMIT_LIMIT;
					break;
				}
				if (System.nanoTime() > deadlineNanos) {
					stopped = TIMEOUT;
					break;
				}
				try (TreeWalk tree = new TreeWalk(reader)) {
					tree.setRecursive(true);
					if (commit.getParentCount() > 0) {
						// Against the first parent only: a merged branch's own commits are walked too.
						tree.addTree(walk.parseCommit(commit.getParent(0)).getTree());
						tree.setFilter(TreeFilter.ANY_DIFF);
					}
					int index = tree.addTree(commit.getTree());
					while (tree.next()) {
						FileMode mode = tree.getFileMode(index);
						if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE && mode != FileMode.SYMLINK) {
							continue;
						}
						String path = tree.getPathString();
						if (!safe(path)) {
							stopped = UNSAFE_PATH;
							break commits;
						}
						ObjectId blob = tree.getObjectId(index);
						if (!seen.add(blob.copy())) {
							continue;
						}
						ObjectLoader loader = reader.open(blob, Constants.OBJ_BLOB);
						long size = loader.getSize();
						boolean extract = mode != FileMode.SYMLINK && this.extracted.test(path);
						boolean notRead = mode != FileMode.SYMLINK && !extract && this.notExtracted.test(path);
						if (notRead || size > limits.blobBytes()) {
							if (unscannedCount++ < LISTED_UNSCANNED) {
								unscanned.add(new UnscannedFile(path, size, notRead ? NOT_EXTRACTED : TOO_LARGE));
							}
							continue;
						}
						decompressed += size;
						if (decompressed > limits.decompressedBytes()) {
							stopped = DECOMPRESSED_LIMIT;
							break commits;
						}
						if (++scanned > limits.blobs()) {
							scanned--;
							stopped = BLOB_LIMIT;
							break commits;
						}
						if ((scanned & 63) == 0 && System.nanoTime() > deadlineNanos) {
							stopped = TIMEOUT;
							break commits;
						}
						byte[] bytes = loader.getCachedBytes(limits.blobBytes());
						List<Found> here = new ArrayList<>();
						Instant when = commit.getAuthorIdent().getWhenAsInstant();
						Optional<Hit> personal;
						if (extract) {
							try {
								personal = this.personalData.findInTables(path, DataFileTables.read(path, bytes,
										limits.blobBytes(), (long) INFLATE_FACTOR * limits.blobBytes()));
							}
							catch (DataFileTables.Unreadable unreadable) {
								if (unscannedCount++ < LISTED_UNSCANNED) {
									unscanned.add(new UnscannedFile(path, size, UNREADABLE));
								}
								continue;
							}
						}
						else {
							if (mode != FileMode.SYMLINK && binary(bytes)) {
								continue;
							}
							String text = pieces(new String(bytes, StandardCharsets.UTF_8), limits.lineLength());
							for (Match match : this.matcher.find(path, text)) {
								here.add(new Found(match.ruleId(), match.type(), path, commit.name(), when, match.masked(),
										match.hash(), Set.of(), 0));
							}
							personal = mode == FileMode.SYMLINK ? Optional.empty() : this.personalData.find(path, text);
						}
						personal.ifPresent(hit -> here.add(new Found(PersonalDataMatcher.RULE_ID, "", path, commit.name(),
								when, String.join(" · ", hit.samples()), hit.hash(), hit.kinds(), hit.lines())));
						for (Found one : here) {
							if (hashes.add(one.hash())) {
								found.add(one);
								if (found.size() >= MAX_FOUND) {
									stopped = MATCH_LIMIT;
									break commits;
								}
							}
						}
					}
				}
			}
		}
		return new Report(found, Math.min(commits, limits.commits()), scanned, unscannedCount, unscanned, stopped);
	}

	/** No absolute path, no empty, {@code .} or {@code ..} segment, and no {@code .git} directory. */
	static boolean safe(String path) {
		if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("\0")) {
			return false;
		}
		for (String segment : path.split("/", -1)) {
			String lower = segment.toLowerCase(Locale.ROOT);
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || lower.equals(".git")) {
				return false;
			}
		}
		return true;
	}

	private static boolean binary(byte[] bytes) {
		int end = Math.min(bytes.length, BINARY_PROBE);
		for (int i = 0; i < end; i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The text with every line longer than {@code length} cut into pieces of that length, each on its
	 * own line and overlapping the previous one by a quarter, so a credential across a cut is still
	 * whole in one piece.
	 */
	static String pieces(String text, int length) {
		int lineStart = 0;
		boolean longLine = false;
		for (int i = 0; i <= text.length(); i++) {
			if (i == text.length() || text.charAt(i) == '\n') {
				if (i - lineStart > length) {
					longLine = true;
					break;
				}
				lineStart = i + 1;
			}
		}
		if (!longLine) {
			return text;
		}
		int step = length - length / 4;
		StringBuilder out = new StringBuilder(text.length() + text.length() / 3);
		for (String line : text.split("\n", -1)) {
			if (line.length() <= length) {
				out.append(line).append('\n');
				continue;
			}
			for (int start = 0;; start += step) {
				int end = Math.min(line.length(), start + length);
				out.append(line, start, end).append('\n');
				if (end == line.length()) {
					break;
				}
			}
		}
		return out.toString();
	}

}
