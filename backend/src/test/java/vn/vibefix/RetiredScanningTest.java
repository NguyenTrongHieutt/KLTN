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
 * Story 23.1 (D8, AD-18, AD-6, AD-22, AD-23): vibefix scans no code, so nothing live names the scan pipeline
 * ({@code Scans}, {@code ScanCompleted}, {@code ScanPurpose}, {@code requestScan}, {@code setResolvers}), a Finding,
 * the scan lane, the {@code SECRET_SCAN} sandbox job, a retired reason, a {@code scanning.*} notification, to-do or
 * email template, or the {@code scanning-fingerprint-key} secret, in {@code backend/src/main} outside its Flyway
 * history, in {@code frontend/src} outside its tests, or in {@code deploy}. Tests may name them: a migration test seeds
 * them. What {@code scanning} keeps (the rules, {@code SensitiveText} and {@code SensitiveChanges}) stays, and only the
 * modules that use it depend on it. The SPA's Job phases {@code FINDING_MENTOR} and {@code FINDING_CREW} are about
 * finding people, and a word-bounded, capitalized {@code Finding} never matches them.
 * <p>
 * Story 23.2 (D9, R25): heads are compared only at acceptance, so nothing live names the head tracking either: the
 * {@code WorkingHeadMoved} event, {@code PushScans}, {@code recordedHead}, the {@code branch_head} and
 * {@code push_scan} tables, or the {@code push-sweep} and {@code void-sweep} settings.
 * <p>
 * Story 23.3 (D1, D5): the platform repositories are private copies, so nothing live outside the S4 and S6 spike
 * commands names the fork ({@code Unnarrowed.FORK}, {@code fork-poll}, {@code fork-wait}, {@code turnFeaturesOff}),
 * the rulesets ({@code createRulesets}, {@code rulesetDifferences}, {@code RULESETS_DIFFER},
 * {@code vibefix-default-branch}, {@code vibefix-handover}), the old feature read ({@code featureDifferences}), or
 * the public-Working outsider audit ({@code outsider_pull_request_closed}).
 */
class RetiredScanningTest {

	static final Path BACKEND_MAIN = Path.of("src", "main");

	static final Path MIGRATIONS = BACKEND_MAIN.resolve(Path.of("resources", "db", "migration"));

	static final Path FRONTEND_SRC = Path.of("..", "frontend", "src");

	static final Path DEPLOY = Path.of("..", "deploy");

	static final Path VI = FRONTEND_SRC.resolve(Path.of("i18n", "vi.json"));

	static final Path MODULES = BACKEND_MAIN.resolve(Path.of("java", "vn", "vibefix"));

	/** Story 23.3's entry, which the spike commands may still name: they keep their own code as CLI history. */
	static final String FORK_AND_RULESETS = "fork and rulesets";

	/** The two spike commands whose probes keep their own fork and ruleset code. */
	static final java.util.Set<String> SPIKE_COMMANDS = java.util.Set.of("SpikeS4Command.java", "SpikeS6Command.java");

	/** What no live file may say, each with what it names. */
	static final Map<String, Pattern> RETIRED = Map.of("the scan pipeline",
			Pattern.compile("\\b(?:Scans|ScanCompleted|ScanPurpose|ScanStatus|ScanSummary|ScanTarget|ShaRange|requestScan"
					+ "|setResolvers|PushScanDelay|JobFindingController|CleanRepositories|Rescans)\\b"),
			"a Finding", Pattern.compile("\\b(?:Findings?|FalsePositive|FindingCategory|FindingStatus|FindingResolution)\\b"),
			"the scan lane", Pattern.compile("\\bLane\\.SCAN\\b"), "the scan sandbox job", Pattern.compile("\\bSECRET_SCAN\\b"),
			"a retired reason",
			Pattern.compile("\\b(?:INTAKE_FINDING_OPEN|FINDING_OPEN|COMMITS_NOT_SCANNED|OPEN_FINDINGS|SCAN_IN_PROGRESS"
					+ "|SCAN_LIMIT|NO_PERSONAL_DATA_FINDING|NOT_A_FALSE_POSITIVE)\\b"),
			"a scanning notification, to-do or template", Pattern.compile("\\bscanning\\.(?:resolve_|intake_scan|finding_|push_scan)"),
			"a promise of a scan in the text people read",
			Pattern.compile("quét bảo mật|đã (?:được )?quét|kiểm tra khóa bí mật", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
			"the fingerprint key", Pattern.compile("scanning[-_.]fingerprint[-_.]key", Pattern.CASE_INSENSITIVE),
			"head tracking",
			Pattern.compile("\\b(?:WorkingHeadMoved|PushScans|recordedHead|branch_head|push_scan|push-sweep|void-sweep)\\b"),
			FORK_AND_RULESETS, Pattern.compile("\\b(?:Unnarrowed\\.FORK|createRulesets|rulesetDifferences|RULESETS_DIFFER"
					+ "|fork-poll|fork-wait|turnFeaturesOff|featureDifferences|vibefix-default-branch|vibefix-handover"
					+ "|outsider_pull_request_closed)\\b"));

	@Test
	void nothingLiveNamesTheScanPipelineAFindingTheScanLaneOrARetiredReason() {
		List<Path> files = new ArrayList<>(liveFiles(BACKEND_MAIN));
		files.addAll(liveFiles(FRONTEND_SRC));
		files.addAll(liveFiles(DEPLOY));

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "api", "openapi.json")))
			.anyMatch(file -> file.endsWith(Path.of("deploy", "compose.yaml")));
		assertThat(files.stream().flatMap(file -> violations(file, read(file)).stream()).toList()).isEmpty();
	}

	@Test
	void noScanningEmailTemplateNotificationOrToDoIsLeft() throws IOException {
		try (Stream<Path> templates = Files.list(BACKEND_MAIN.resolve(Path.of("resources", "templates", "email")))) {
			assertThat(templates.map(file -> file.getFileName().toString())).noneMatch(name -> name.startsWith("scanning."));
		}
		JsonNode vi = JsonMapper.builder().build().readTree(read(VI));
		assertThat(vi.path("notification").has("scanning")).as("notification.scanning").isFalse();
		assertThat(vi.path("todo").has("scanning")).as("todo.scanning").isFalse();
		assertThat(read(MODULES.resolve(Path.of("notification", "internal", "NotificationTypes.java"))))
			.doesNotContain("\"scanning.");
	}

	@Test
	void scanningKeepsTheRulesAndOnlyTheModulesThatUseThemDependOnIt() {
		assertThat(packageInfo("scanning")).contains("allowedDependencies = { \"shared\" }");
		for (String module : List.of("product", "admin")) {
			assertThat(packageInfo(module)).as(module).doesNotContain("\"scanning\"");
		}
		for (String module : List.of("repo", "delivery", "job", "work", "chat")) {
			assertThat(packageInfo(module)).as(module).contains("\"scanning\"");
		}
		assertThat(BACKEND_MAIN.resolve(Path.of("resources", "scanning", "rules.yaml"))).isRegularFile();
		try (Stream<Path> files = Files.walk(MODULES.resolve("scanning"))) {
			assertThat(files.filter(Files::isRegularFile).map(file -> file.getFileName().toString()).sorted())
				.containsExactly("PathSensitiveChanges.java", "PersonalDataMatcher.java", "ScanRules.java", "SecretMatcher.java",
						"SensitiveChanges.java", "SensitiveText.java", "SensitiveTextCheck.java", "package-info.java");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Test
	void theRulesFindEachRetiredNameAndLeaveTheLiveOnesAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("import vn.vibefix.scanning.Scans;", "ScanCompleted completed", "ScanPurpose.J7",
				"this.scans.requestScan(target)", "scans.setResolvers(id, ids)", "a Finding opened", "open Findings",
				"FalsePositive item", "Lane.SCAN", "\"SECRET_SCAN\"", "INTAKE_FINDING_OPEN", "DeliveryReason.FINDING_OPEN",
				"COMMITS_NOT_SCANNED", "JobReason.OPEN_FINDINGS", "SCAN_LIMIT", "'scanning.resolve_work_finding'",
				"\"scanning.finding_opened\"", "scanning.push_scan_delayed", "scanning.intake_scan_clean",
				"- scanning-fingerprint-key", "SCANNING_FINGERPRINT_KEY", "khi các thay đổi mới đã được quét bảo mật",
				"Đã quét lịch sử mã", "Kiểm tra khóa bí mật: không còn vấn đề nào mở.",
				"import vn.vibefix.repo.WorkingHeadMoved;", "class PushScans {", "this.branches.recordedHead(jobId)",
				"SELECT sha FROM repo.branch_head", "INSERT INTO repo.push_scan", "    push-sweep: PT10M",
				"${vibefix.delivery.void-sweep:PT10M}", "withUnnarrowed(App.PLATFORM, Unnarrowed.FORK, call)",
				"repositories().createRulesets(intermediary);", "this.repositories.rulesetDifferences(repo.repository())",
				"new Outcome.Transient(\"RULESETS_DIFFER\")", "@Value(\"${vibefix.github.fork-poll:2s}\")",
				"registry.add(\"vibefix.github.fork-wait\", () -> \"5s\");", "repositories().turnFeaturesOff(working);",
				"this.repositories.featureDifferences(repo.repository())", "static final String DEFAULT = \"vibefix-default-branch\";",
				"\"vibefix-handover\"", "audit(\"repo.outsider_pull_request_closed\", jobId);")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("import vn.vibefix.scanning.SensitiveText;", "SensitiveChanges.WORKFLOWS", "FINDING_MENTOR",
				"case FINDING_CREW ->", "scanning/rules.yaml", "One finding.", "finding people for the Job",
				"vibefix scans no code", "Lane.GITHUB_LONG", "Tôi hiểu vibefix không quét mã.", "HandoverVoids",
				"HeadMoved moved", "grant-sweep: PT10M", "platform-sweep: PT10M", "branches.working(jobId)", "refs/heads/main",
				"repositories().settingsDifferences(working)", "Unnarrowed.REPOSITORY_CREATION", "forkPoll",
				"\"allow_forking\", false")) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
	}

	/**
	 * Story 23.3: the spike commands keep their own fork and ruleset code as CLI history, so the "fork and rulesets"
	 * entry does not read them; every other entry still does, and it reads every other file.
	 */
	@Test
	void theForkAndRulesetEntryLeavesTheSpikeCommandsAlone() {
		Path s4 = MODULES.resolve(Path.of("github", "internal", "SpikeS4Command.java"));
		String probe = read(s4);

		assertThat(probe).as("the S4 probe still creates its rulesets").contains("createRulesets(");
		assertThat(violations(s4, probe)).isEmpty();
		assertThat(violations(s4, "private Check createRulesets(String organization, Run run) {")).isEmpty();
		assertThat(violations(s4, "import vn.vibefix.scanning.Scans;")).as("the other entries still apply").isNotEmpty();
		assertThat(violations(Path.of("JobSetupOperation.java"), "private Check createRulesets(String organization, Run run) {"))
			.isNotEmpty();
		assertThat(violations(Path.of("SpikeS7Command.java"), "private Check createRulesets(String organization, Run run) {"))
			.as("only the two spike commands that keep this code are exempt")
			.isNotEmpty();
	}

	/** Each line of {@code text} a retired name appears on, as {@code file:line: what}. */
	static List<String> violations(Path file, String text) {
		List<String> violations = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		boolean spike = SPIKE_COMMANDS.contains(file.getFileName().toString());
		for (int number = 0; number < lines.length; number++) {
			for (Map.Entry<String, Pattern> retired : RETIRED.entrySet()) {
				if (spike && retired.getKey().equals(FORK_AND_RULESETS)) {
					continue;
				}
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
				.filter(RetiredScanningTest::isText)
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
				".js", ".sh", ".md", ".toml", ".conf").anyMatch(name::endsWith) || name.equals("Caddyfile");
	}

	private static String packageInfo(String module) {
		return read(MODULES.resolve(Path.of(module, "package-info.java")));
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
