package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Story 23.5b (FR-13, FR-20, R7, R31): the Source Repository's contributors and the daily drift poll are read through
 * the Customer's Source App. "Made private" is no longer drift, so nothing live names the retired kind
 * {@code DELETED_OR_PRIVATE}, alone or inside a key such as {@code kindDELETED_OR_PRIVATE}; and no path reads
 * contributors with the linking token, so nothing live names {@code hasContributor}. Both are searched in
 * {@code backend/src/main} outside its Flyway history (whose new migration maps old rows to {@code GONE}), in
 * {@code frontend/src} outside its tests, and in {@code deploy}. The one exemption is the admin evidence label
 * {@code screen.admin.disputes.evidence.drift.DELETED_OR_PRIVATE} in {@code vi.json}, which Evidence Packages
 * snapshotted earlier may still name.
 */
class RetiredDriftPrivateTest {

	static final Pattern RETIRED = Pattern.compile("DELETED_OR_PRIVATE|hasContributor");

	/** The path of the one exempt label in {@code vi.json}. */
	static final List<String> EXEMPT_LABEL = List.of("screen", "admin", "disputes", "evidence", "drift", "DELETED_OR_PRIVATE");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void nothingLiveNamesTheRetiredDriftKindOrTheTokenContributorRead() {
		List<Path> files = new ArrayList<>(RetiredScanningTest.liveFiles(RetiredScanningTest.BACKEND_MAIN));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.FRONTEND_SRC));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.DEPLOY));

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "i18n", "vi.json")))
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "features", "job", "DriftWarning.tsx")))
			.anyMatch(file -> file.endsWith(Path.of("java", "vn", "vibefix", "repo", "internal", "SourceDrift.java")));
		assertThat(files.stream().flatMap(file -> violations(file, live(file)).stream()).toList()).isEmpty();
	}

	@Test
	void theAdminEvidenceLabelStaysForEvidenceSnapshottedEarlier() {
		JsonNode label = JSON.readTree(read(RetiredScanningTest.VI));
		for (String key : EXEMPT_LABEL) {
			label = label.path(key);
		}
		assertThat(label.isString()).as("screen.admin.disputes.evidence.drift.DELETED_OR_PRIVATE").isTrue();
	}

	@Test
	void theRuleFindsEachRetiredNameAndLeavesTheNewOnesAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("Kind.DELETED_OR_PRIVATE", "\"kindDELETED_OR_PRIVATE\": \"…\"",
				"this.repositories.hasContributor(lan, \"lan\", \"so-dat-ban\", mentor)")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("Kind.GONE, Kind.APP_UNINSTALLED, Kind.WORKFLOW_CHANGED", "ProductSources#contributors",
				"\"private\": true")) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
		String vi = """
				{"screen": {"admin": {"disputes": {"evidence": {"drift": {"DELETED_OR_PRIVATE": "Kho nguồn bị xóa"}}}},
				 "job": {"drift": {"kindDELETED_OR_PRIVATE": "Dự án gốc"}}}}
				""";
		assertThat(violations(RetiredScanningTest.VI, exempt(vi))).as("only the exempt label is left out").singleElement()
			.asString()
			.contains("kindDELETED_OR_PRIVATE");
	}

	/** The file's text, the exempt label left out of {@code vi.json}. */
	private static String live(Path file) {
		String text = read(file);
		return file.normalize().equals(RetiredScanningTest.VI.normalize()) ? exempt(text) : text;
	}

	/** {@code vi.json} without the exempt admin evidence label, written one key per line. */
	private static String exempt(String vi) {
		JsonNode root = JSON.readTree(vi);
		JsonNode parent = root;
		for (String key : EXEMPT_LABEL.subList(0, EXEMPT_LABEL.size() - 1)) {
			parent = parent.path(key);
		}
		if (parent instanceof ObjectNode drift) {
			drift.remove(EXEMPT_LABEL.getLast());
		}
		return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
	}

	private static List<String> violations(Path file, String text) {
		List<String> violations = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int number = 0; number < lines.length; number++) {
			if (RETIRED.matcher(lines[number]).find()) {
				violations.add(file + ":" + (number + 1) + ": " + lines[number].strip());
			}
		}
		return violations;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
