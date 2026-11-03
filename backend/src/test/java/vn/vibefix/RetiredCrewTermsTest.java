package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SG-63, AD-23, AD-40 (Story 22.4): money moves only through the Milestone Splits, so nothing live names the Crew Terms,
 * the share holds, the {@code SHARE} subject of a Team Issue, or a retired reason, in {@code backend/src/main} outside its
 * Flyway history and in {@code frontend/src} outside its tests. Tests may name them: a migration test seeds them. SQL's
 * {@code FOR SHARE} is a row lock, and a word-bounded {@code SHARE} never matches a name such as the canary
 * {@code CREW_SHARE}. {@code MILESTONE_NOT_HELD} left {@code job}, {@code payment} and {@code dispute} only: {@code work}
 * and {@code delivery} guard unrelated commands with it.
 */
class RetiredCrewTermsTest {

	static final Path BACKEND_MAIN = Path.of("src", "main");

	static final Path MIGRATIONS = BACKEND_MAIN.resolve(Path.of("resources", "db", "migration"));

	static final Path FRONTEND_SRC = Path.of("..", "frontend", "src");

	static final Path VI = FRONTEND_SRC.resolve(Path.of("i18n", "vi.json"));

	/** What no live file may say, each with what it names. */
	static final Map<String, Pattern> RETIRED = Map.of("the Crew Terms", Pattern.compile("crew.?terms", Pattern.CASE_INSENSITIVE),
			"the share holds", Pattern.compile("share.?hold|hold.?shares", Pattern.CASE_INSENSITIVE), "the SHARE subject",
			Pattern.compile("(?<!FOR )\\bSHARE\\b"), "a retired reason",
			Pattern.compile("\\b(?:SHARE_TRANSFER_TOO_LARGE|CREW_NOT_CONFIRMED|CREW_LOCKED|CREW_CHANGE_REQUIRED"
					+ "|CREW_INACTIVE_PARTY|SHARES_INVALID|PAYEES)\\b"));

	static final Pattern NOT_HELD = Pattern.compile("\\bMILESTONE_NOT_HELD\\b");

	@Test
	void nothingLiveNamesTheCrewTermsTheShareHoldsTheShareSubjectOrARetiredReason() {
		List<Path> files = new ArrayList<>(liveFiles(BACKEND_MAIN));
		files.addAll(liveFiles(FRONTEND_SRC));

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "api", "openapi.json")));
		assertThat(files.stream().flatMap(file -> violations(file, read(file)).stream()).toList()).isEmpty();
	}

	@Test
	void milestoneNotHeldLeftTheModulesWhoseShareIssuesUsedIt() {
		for (String reason : List.of("job/internal/JobReason.java", "payment/internal/PaymentReason.java",
				"dispute/internal/DisputeReason.java")) {
			assertThat(NOT_HELD.matcher(read(javaSource(reason))).find()).as(reason).isFalse();
		}
		for (String reason : List.of("work/internal/WorkReason.java", "delivery/internal/DeliveryReason.java")) {
			assertThat(NOT_HELD.matcher(read(javaSource(reason))).find()).as("%s keeps it for its own command", reason).isTrue();
		}
		JsonNode reasons = JsonMapper.builder().build().readTree(read(VI)).path("reason");
		for (String module : List.of("job", "payment", "dispute")) {
			assertThat(reasons.path(module).has("MILESTONE_NOT_HELD")).as("reason.%s", module).isFalse();
		}
		assertThat(reasons.path("work").has("MILESTONE_NOT_HELD")).isTrue();
	}

	@Test
	void theRulesFindEachRetiredNameAndLeaveTheLiveOnesAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("the Crew Terms version", "crew_terms_version_no", "CrewTermsGrants", "crewTerms", "CREW_TERMS",
				"payment.share_hold", "ShareHolds", "holdShares", "subject = 'SHARE'", "Subject.SHARE", "SHARE_TRANSFER_TOO_LARGE",
				"CREW_INACTIVE_PARTY", "PAYEES")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("SELECT id FROM job.job WHERE id = :id FOR SHARE", "CREW_SHARE", "NOT_SHAREABLE",
				"Pages.Visibility.SHARED", "a share of the pool", "MILESTONE_NOT_HELD", "the Crew's terms of work")) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
	}

	/** Each line of {@code text} a retired name appears on, as {@code file:line: what}. */
	static List<String> violations(Path file, String text) {
		List<String> violations = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int number = 0; number < lines.length; number++) {
			for (Map.Entry<String, Pattern> retired : RETIRED.entrySet()) {
				Matcher matcher = retired.getValue().matcher(lines[number]);
				if (matcher.find()) {
					violations.add(file + ":" + (number + 1) + ": names " + retired.getKey() + " (" + matcher.group() + ")");
				}
			}
		}
		return violations;
	}

	/** The files under {@code root} that are neither Flyway history nor tests. */
	static List<Path> liveFiles(Path root) {
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(Files::isRegularFile)
				.filter(file -> !file.startsWith(MIGRATIONS))
				.filter(file -> !isTest(file))
				.filter(RetiredCrewTermsTest::isText)
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static boolean isTest(Path file) {
		String name = file.getFileName().toString();
		return name.contains(".test.") || file.startsWith(FRONTEND_SRC.resolve("test"));
	}

	private static boolean isText(Path file) {
		String name = file.getFileName().toString();
		return Stream.of(".java", ".ts", ".tsx", ".json", ".txt", ".sql", ".yaml", ".yml", ".properties", ".css", ".html", ".mjs",
				".js").anyMatch(name::endsWith);
	}

	private static Path javaSource(String path) {
		return BACKEND_MAIN.resolve(Path.of("java", "vn", "vibefix")).resolve(path);
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
