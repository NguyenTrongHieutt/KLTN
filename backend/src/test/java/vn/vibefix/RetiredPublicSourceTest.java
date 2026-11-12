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

/**
 * Story 23.5 (AD-13, R2, R7): the Source Repository is read through the Customer's Source App, so a private
 * repository is accepted and "made private" is no longer a block. Nothing live names the retired reasons
 * {@code REPO_PRIVATE} and {@code SOURCE_NOT_PUBLIC}, alone or inside a key such as {@code blockedSOURCE_NOT_PUBLIC},
 * in {@code backend/src/main} outside its Flyway history (whose new migrations map old rows to
 * {@code SOURCE_APP_MISSING}), in {@code frontend/src} outside its tests, or in {@code deploy}.
 */
class RetiredPublicSourceTest {

	static final Pattern RETIRED = Pattern.compile("REPO_PRIVATE|SOURCE_NOT_PUBLIC");

	@Test
	void nothingLiveNamesTheRetiredPublicSourceReasons() {
		List<Path> files = new ArrayList<>(RetiredScanningTest.liveFiles(RetiredScanningTest.BACKEND_MAIN));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.FRONTEND_SRC));
		files.addAll(RetiredScanningTest.liveFiles(RetiredScanningTest.DEPLOY));

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "i18n", "vi.json")))
			.anyMatch(file -> file.endsWith(Path.of("deploy", "customer", "vibefix.yml")));
		assertThat(files.stream().flatMap(file -> violations(file, read(file)).stream()).toList()).isEmpty();
	}

	@Test
	void theRuleFindsEachRetiredReasonAndLeavesTheNewOnesAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("ProductReason.REPO_PRIVATE", "\"blockedSOURCE_NOT_PUBLIC\": \"…\"",
				"'BLOCKED', 'SOURCE_NOT_PUBLIC'")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("Block.SOURCE_APP_MISSING, Block.SOURCE_APP_WRONG_REPOSITORY", "WORKFLOW_MISMATCH",
				"\"private\": true")) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
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
