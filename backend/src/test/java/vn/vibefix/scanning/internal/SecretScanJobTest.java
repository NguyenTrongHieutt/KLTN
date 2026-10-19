package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.scanning.internal.PlantedSecrets.Planted;

/**
 * SM-7, AD-32, SG-14: the {@value SecretScanJobHandler#TYPE} job on local repositories, cloned over
 * {@code file:} as it clones GitHub over HTTPS.
 */
class SecretScanJobTest {

	@TempDir
	Path root;

	@AfterEach
	void release() {
		TestRepositories.releaseFiles();
	}

	@Test
	void theSm7RepositoryHasEveryPlantedCredentialDetectedOnceAtItsFirstCommit() {
		Map<String, Planted> planted = PlantedSecrets.all();
		List<Planted> samples = new ArrayList<>(planted.values());
		Map<String, String> addedIn = new HashMap<>();
		try (Git git = TestRepositories.create(this.root, "sm7/shop-gom")) {
			Instant time = Instant.parse("2026-09-01T03:00:00Z");
			RevCommit first = TestRepositories.commit(git, time, files(samples.subList(0, 10)), Set.of());
			samples.subList(0, 10).forEach(sample -> addedIn.put(sample.ruleId(), first.name()));
			Map<String, String> second = files(samples.subList(10, 20));
			second.put("README.md", "# Shop Gốm\n");
			RevCommit secondCommit = TestRepositories.commit(git, time.plusSeconds(60), second, Set.of());
			samples.subList(10, 20).forEach(sample -> addedIn.put(sample.ruleId(), secondCommit.name()));
			// Deleted later, still in the history; one moved to another file, still one Finding.
			Map<String, String> moved = new LinkedHashMap<>();
			moved.put("old/" + samples.get(0).path(), samples.get(0).text());
			TestRepositories.commit(git, time.plusSeconds(120), moved,
					Set.of(samples.get(0).path(), samples.get(1).path(), samples.get(12).path()));
			git.checkout().setCreateBranch(true).setName("experiment").call();
			RevCommit branch = TestRepositories.commit(git, time.plusSeconds(180), files(samples.subList(20, samples.size())),
					Set.of());
			samples.subList(20, samples.size()).forEach(sample -> addedIn.put(sample.ruleId(), branch.name()));
			git.checkout().setName("main").call();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		JsonNode output = scan("sm7/shop-gom", ScanLimits.DEFAULT);

		assertThat(output.has("refused")).as(output.toString()).isFalse();
		assertThat(output.has("stopped")).isFalse();
		Map<String, JsonNode> found = new HashMap<>();
		output.path("found").forEach(match -> found.put(match.path("ruleId").stringValue(), match));
		assertThat(found.keySet()).as("100%% of the planted credentials").containsExactlyInAnyOrderElementsOf(planted.keySet());
		assertThat(output.path("found")).hasSize(planted.size());
		for (Planted sample : planted.values()) {
			JsonNode match = found.get(sample.ruleId());
			assertThat(match.path("path").stringValue()).as(sample.ruleId()).isEqualTo(sample.path());
			assertThat(match.path("commit").stringValue()).as(sample.ruleId()).isEqualTo(addedIn.get(sample.ruleId()));
			assertThat(match.path("masked").stringValue()).isEqualTo(SecretMatcher.mask(sample.value()));
			assertThat(match.path("hash").stringValue()).isEqualTo(SecretMatcher.hash(sample.ruleId(), sample.value()));
		}
		String text = output.toString();
		assertThat(planted.values()).allSatisfy(sample -> assertThat(text).doesNotContain(sample.value()));
		assertThat(output.path("commits").intValue()).isEqualTo(4);
	}

	@Test
	void theSm7RepositoryHasEveryPlantedPersonalDataFileDetectedAndSpreadsheetsListed() {
		Map<String, Map.Entry<String, Set<ScanRules.PersonalKind>>> planted = PlantedPersonalData.all();
		List<String> paths = new ArrayList<>(planted.keySet());
		try (Git git = TestRepositories.create(this.root, "sm7/personal")) {
			Instant time = Instant.parse("2026-09-01T03:00:00Z");
			Map<String, String> first = new LinkedHashMap<>();
			paths.subList(0, 3).forEach(path -> first.put(path, planted.get(path).getKey()));
			first.put("README.md", "Liên hệ 0912345678 hoặc lan@shopgom.vn\n");
			first.put("exports/khach-hang.xlsx", "PK not really a spreadsheet\n");
			TestRepositories.commit(git, time, first, Set.of());
			Map<String, String> second = new LinkedHashMap<>();
			paths.subList(3, paths.size()).forEach(path -> second.put(path, planted.get(path).getKey()));
			TestRepositories.commit(git, time.plusSeconds(60), second, Set.of());
			// Removed from the code, still in the history.
			TestRepositories.commit(git, time.plusSeconds(120), Map.of(), Set.of(paths.get(0), paths.get(4)));
		}

		JsonNode output = scan("sm7/personal", ScanLimits.DEFAULT);

		Map<String, JsonNode> found = new HashMap<>();
		output.path("found").forEach(match -> {
			assertThat(match.path("category").stringValue()).isEqualTo("PERSONAL_DATA");
			found.put(match.path("path").stringValue(), match);
		});
		assertThat(found.keySet()).as("100%% of the planted personal data").containsExactlyInAnyOrderElementsOf(paths);
		for (String path : paths) {
			Set<String> kinds = new java.util.HashSet<>();
			found.get(path).path("kinds").forEach(kind -> kinds.add(kind.stringValue()));
			assertThat(kinds).as(path)
				.containsExactlyInAnyOrderElementsOf(planted.get(path).getValue().stream().map(Enum::name).toList());
			assertThat(found.get(path).path("masked").stringValue()).as(path).contains("•");
		}
		String text = output.toString();
		assertThat(text).doesNotContain(PlantedPersonalData.phone(0)).doesNotContain("khach.hang0@gmail.com")
			.doesNotContain("079203001234").doesNotContain("123456789");
		assertThat(output.path("unscanned")).singleElement().satisfies(file -> {
			assertThat(file.path("path").stringValue()).isEqualTo("exports/khach-hang.xlsx");
			assertThat(file.path("reason").stringValue()).isEqualTo(HistoryScanner.NOT_EXTRACTED);
		});
	}

	@Test
	void symbolicLinksAreReadAsTheirTargetTextAndNeverFollowed() {
		try (Git git = TestRepositories.create(this.root, "sm7/links")) {
			TestRepositories.rawCommit(git.getRepository(), Map.of("environ", Map.entry(FileMode.SYMLINK, "/proc/self/environ"),
					"passwd", Map.entry(FileMode.SYMLINK, "/etc/passwd"), "hosts", Map.entry(FileMode.SYMLINK, "../../../etc/hosts"),
					"README.md", Map.entry(FileMode.REGULAR_FILE, "# Links\n")));
		}

		JsonNode output = scan("sm7/links", ScanLimits.DEFAULT);

		assertThat(output.path("found")).isEmpty();
		assertThat(output.path("scannedBlobs").intValue()).isEqualTo(4);
		String text = output.toString();
		assertThat(text).doesNotContain("root:").doesNotContain("localhost");
		String path = System.getenv("PATH");
		if (path != null) {
			assertThat(text).doesNotContain(path);
		}
	}

	@Test
	void aFileOverTheSizeLimitIsListedAsUnscannedNotRead() {
		Planted stripe = PlantedSecrets.all().get("stripe-secret-key");
		try (Git git = TestRepositories.create(this.root, "sm7/big")) {
			TestRepositories.commit(git, Instant.parse("2026-09-01T03:00:00Z"),
					Map.of("public/video.txt", "v".repeat(3000) + stripe.text(), "src/app.ts", "export {}\n"), Set.of());
		}

		JsonNode output = scan("sm7/big", limits(1024, 50_000, 300L * 1024 * 1024));

		assertThat(output.path("found")).isEmpty();
		assertThat(output.path("unscannedCount").intValue()).isOne();
		assertThat(output.path("unscanned").get(0).path("path").stringValue()).isEqualTo("public/video.txt");
		assertThat(output.path("unscanned").get(0).path("sizeBytes").longValue()).isEqualTo(3000L + stripe.text().length());
	}

	@Test
	void theCommitCapStopsTheWalkAndSaysSo() {
		try (Git git = TestRepositories.create(this.root, "sm7/long")) {
			for (int i = 0; i < 3; i++) {
				TestRepositories.commit(git, Instant.parse("2026-09-01T03:00:00Z").plusSeconds(i * 60L),
						Map.of("file-" + i + ".txt", "change " + i + "\n"), Set.of());
			}
		}

		JsonNode output = scan("sm7/long", limits(1024 * 1024, 2, 300L * 1024 * 1024));

		assertThat(output.path("stopped").stringValue()).isEqualTo(HistoryScanner.COMMIT_LIMIT);
		assertThat(output.path("commits").intValue()).isEqualTo(2);
	}

	@Test
	void theFetchIsCancelledPastTheTransferCap() {
		try (Git git = TestRepositories.create(this.root, "sm7/heavy")) {
			Map<String, String> files = new LinkedHashMap<>();
			for (int i = 0; i < 200; i++) {
				files.put("data/file-" + i + ".txt", PlantedSecrets.alnum(4000, i));
			}
			TestRepositories.commit(git, Instant.parse("2026-09-01T03:00:00Z"), files, Set.of());
		}

		JsonNode output = scan("sm7/heavy", limits(1024 * 1024, 50_000, 1));

		assertThat(output.path("refused").stringValue()).isEqualTo(SecretScanJobHandler.TRANSFER_LIMIT);
	}

	@Test
	void aTreeWithADotDotOrGitNameIsRefusedAtTheClone() {
		try (Git git = TestRepositories.create(this.root, "sm7/escape")) {
			TestRepositories.rawCommit(git.getRepository(), Map.of("..", Map.entry(FileMode.REGULAR_FILE, "escape\n"),
					"README.md", Map.entry(FileMode.REGULAR_FILE, "# Escape\n")));
		}
		try (Git git = TestRepositories.create(this.root, "sm7/dotgit")) {
			TestRepositories.rawCommit(git.getRepository(), Map.of(".GIT", Map.entry(FileMode.REGULAR_FILE, "[core]\n")));
		}

		assertThat(scan("sm7/escape", ScanLimits.DEFAULT).path("refused").stringValue())
			.isEqualTo(SecretScanJobHandler.UNSAFE_REPOSITORY);
		assertThat(scan("sm7/dotgit", ScanLimits.DEFAULT).path("refused").stringValue())
			.isEqualTo(SecretScanJobHandler.UNSAFE_REPOSITORY);
	}

	@Test
	void theInputIsChecked() {
		SecretScanJobHandler handler = handler();
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		ScanLimits.DEFAULT.write(input.putObject("limits"));
		for (String repository : List.of("../etc", "owner/..", "owner", "owner/name/extra", "-owner/name")) {
			input.put("repository", repository);
			assertThatThrownBy(() -> handler.handle(input)).as(repository).isInstanceOf(IllegalArgumentException.class);
		}
		input.put("repository", "sm7/any");
		input.putObject("limits").put("commits", 1);
		assertThatThrownBy(() -> handler.handle(input)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> limits(2 * 1024 * 1024, 1, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aMissingRepositoryFailsTheJobSoTheOperationTriesAgain() {
		assertThatThrownBy(() -> scan("sm7/missing", ScanLimits.DEFAULT)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("sm7/missing");
	}

	private JsonNode scan(String repository, ScanLimits limits) {
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("repository", repository);
		limits.write(input.putObject("limits"));
		return handler().handle(input);
	}

	private SecretScanJobHandler handler() {
		return new SecretScanJobHandler(
				new MockEnvironment().withProperty("vibefix.github.git-url", TestRepositories.gitUrl(this.root)));
	}

	private static ScanLimits limits(int blobBytes, int commits, long transferBytes) {
		ScanLimits seed = ScanLimits.DEFAULT;
		return new ScanLimits(transferBytes, commits, seed.blobs(), seed.decompressedBytes(), blobBytes, seed.lineLength(),
				Duration.ofMinutes(2));
	}

	private static Map<String, String> files(List<Planted> samples) {
		Map<String, String> files = new LinkedHashMap<>();
		samples.forEach(sample -> files.put(sample.path(), sample.text()));
		return files;
	}

}
