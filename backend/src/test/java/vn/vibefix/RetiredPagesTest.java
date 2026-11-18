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
 * Story 24.1 (D2, FR-29, FR-30, FR-31, FR-70, PRD §4.8 removed): the platform manages no documents, so nothing
 * live names the Documentation Space -- its tables ({@code work.page}, {@code page_version}, {@code page_share},
 * {@code delivery.handover_page}), the Page references a handover and a follow-up carried
 * ({@code report_page_id}, {@code recommendation_no}), the Page templates, the {@code PAGES} read scope, the
 * {@code work.pages_shared} notice, JOB-3's {@code tai-lieu} routes, or the words for it the Customer read
 * ("mục Tài liệu") -- in {@code backend/src/main} outside its Flyway history, or in {@code frontend/src}
 * outside its tests. Tests may name them: the migration test seeds them, and the retired-path test calls them.
 * <p>
 * Two things keep their name on purpose. {@code screen.admin.reports.kind.PAGE} labels the Abuse Reports filed
 * on a Page before this story, which no migration deletes, so ADM-7 still has to name their kind; the kind is
 * gone from {@code AbuseReports.KINDS} and {@code ContentHiding.KINDS}, so no new one can carry it. And the
 * Assessment Report template of {@code job} stays: it is where an Assessment Job's Acceptance Criteria come
 * from, and it no longer tells the Customer to open a document.
 */
class RetiredPagesTest {

	static final Path BACKEND_MAIN = Path.of("src", "main");

	static final Path MIGRATIONS = BACKEND_MAIN.resolve(Path.of("resources", "db", "migration"));

	static final Path FRONTEND_SRC = Path.of("..", "frontend", "src");

	static final Path VI = FRONTEND_SRC.resolve(Path.of("i18n", "vi.json"));

	/** The one live {@code PAGE} left: ADM-7's label for the Abuse Reports filed before this story. */
	static final String HISTORICAL_ABUSE_LABEL = "\"PAGE\": \"Trang\"";

	/** What no live file may say, each with what it names. */
	static final Map<String, Pattern> RETIRED = Map.of("a Page table",
			Pattern.compile("\\bwork\\.page\\b|\\bpage_version\\b|\\bpage_share\\b|\\bhandover_page\\b"),
			"a Page reference of a handover or a follow-up", Pattern.compile("\\b(?:report_page_id|report_version_no"
					+ "|reportPageId|reportVersionNo|recommendation_no|recommendationNo)\\b"),
			"the Page templates", Pattern.compile("\\b(?:PageTemplates|ASSESSMENT_REPORT|page-templates)\\b"),
			"the PAGES read scope", Pattern.compile("AdminReadScope\\s*\\.\\s*PAGES|\"scope\": \"PAGES\""),
			"the shared-Pages notice", Pattern.compile("\\bpages_shared\\b|\\bWORK_PAGES_SHARED\\b"),
			"JOB-3's routes", Pattern.compile("tai-lieu"),
			"the Documentation Space", Pattern.compile("Documentation Space|Documentation-Space"),
			"the Documentation Space, in the Customer's words",
			Pattern.compile("mục Tài liệu", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
			"a Page class", Pattern.compile("\\b(?:PageController|PageShareController|PageShares|PageModeration"
					+ "|PageTreeView|PageVersionView|PageLinkView|PageUpdate|SnapshotPage|ReportVersion)\\b"),
			"a retired Page reason", Pattern.compile("\\b(?:PAGE_TOO_DEEP|PAGE_PARENT_UNKNOWN|TEMPLATE_NOT_AVAILABLE"
					+ "|REPORT_EXISTS|REPORT_ALWAYS_VISIBLE|SHARING_CLOSED|NOT_SHAREABLE|RECOMMENDATION_UNKNOWN"
					+ "|REPORT_MISSING)\\b"));

	@Test
	void nothingLiveNamesAPageATemplateThePagesScopeOrJobThree() {
		List<Path> files = new ArrayList<>(liveFiles(BACKEND_MAIN));
		files.addAll(liveFiles(FRONTEND_SRC));

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "api", "openapi.json")))
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "i18n", "vi.json")));
		assertThat(files.stream().flatMap(file -> violations(file, read(file)).stream()).toList()).isEmpty();
	}

	/** The Abuse Report kind is history ADM-7 still names; nothing may file a new report or hide content under it. */
	@Test
	void onlyTheAbuseReportLabelOfTheReportsFiledBeforeKeepsThePageKind() {
		JsonNode vi = JsonMapper.builder().build().readTree(read(VI));

		assertThat(vi.at("/screen/admin/reports/kind/PAGE").asString()).isEqualTo("Trang");
		assertThat(vi.at("/screen/admin/moderation/kind").has("PAGE")).as("ADM-6 hides no Page").isFalse();
		assertThat(vi.at("/screen/admin/moderation/effects").has("PAGE")).isFalse();
		assertThat(vi.path("screen").has("docs")).as("JOB-3's screen").isFalse();
		assertThat(vi.path("screen").has("pageShares")).isFalse();
		assertThat(vi.path("screen").has("sharedDocs")).isFalse();
		assertThat(vi.path("notification").path("work").has("pages_shared")).isFalse();
		for (String kind : List.of("PAGE")) {
			assertThat(read(javaSource("admin/internal/AbuseReports.java"))).as("AbuseReports.KINDS")
				.doesNotContain("\"" + kind + "\", \"RATING\"");
			assertThat(read(javaSource("admin/internal/ContentHiding.java"))).as("ContentHiding.KINDS")
				.doesNotContain("\"" + kind + "\"");
		}
	}

	/** The Assessment Report template stays, as FR-10's criteria, and sends the Customer to the handover now. */
	@Test
	void theAssessmentReportTemplateStaysAndNamesNoDocument() {
		String template = read(BACKEND_MAIN.resolve(Path.of("resources", "job", "assessment-report-template.yaml")));

		assertThat(template).contains("version: 3").contains("Báo cáo bàn giao").doesNotContain("mục Tài liệu");
		assertThat(violations(Path.of("assessment-report-template.yaml"), template)).isEmpty();
	}

	@Test
	void theRulesFindEachRetiredNameAndLeaveTheLiveOnesAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("DELETE FROM work.page WHERE job_id = :job", "SELECT page_id FROM work.page_version",
				"INSERT INTO work.page_share", "delivery.handover_page", "handover.report_page_id", "pending.reportPageId()",
				"recommendation_no", "content.recommendationNo()", "PageTemplates.Template", "template = 'ASSESSMENT_REPORT'",
				"resources/work/page-templates.yaml", "AdminReadScope.PAGES", "all(AdminReadScope.PAGES)",
				"items @> '[{\"scope\": \"PAGES\"}]'",
				"work.pages_shared", "WORK_PAGES_SHARED", "/cong-viec/${jobId}/tai-lieu", "the Documentation Space",
				"Mở trang Báo cáo đánh giá trong mục Tài liệu.", "class PageController {", "PAGE_TOO_DEEP",
				"DeliveryReason.REPORT_MISSING", "WorkReason.NOT_SHAREABLE")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("PageResponse.of(items, total)", "AuditReader.Page found", "EvidenceAuditPage",
				"param(\"page\", page)", "the run's own page on GitHub", "usePageTitle(t('screen.handover.form.title'))",
				"<PageHeading>{me.title}</PageHeading>", "followUp.recommendationText()", "tài liệu bàn giao",
				"PageRequest.of(page, size)", "MAX_CONTRIBUTOR_PAGES", "const PAGES = ['/so-du']",
				"vibefix keeps no documents (Story 24.1)", HISTORICAL_ABUSE_LABEL)) {
			assertThat(violations(file, text)).as(text).isEmpty();
		}
	}

	/** Each line of {@code text} a retired name appears on, as {@code file:line: what}. */
	static List<String> violations(Path file, String text) {
		List<String> violations = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		for (int number = 0; number < lines.length; number++) {
			if (lines[number].contains(HISTORICAL_ABUSE_LABEL)) {
				continue;
			}
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
				.filter(RetiredPagesTest::isText)
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
