package vn.vibefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 24.2 (D1, FR-22 to FR-26, FR-76 removed, FR-28, FR-32, FR-48, FR-50, FR-62, FR-69, FR-84, AD-2, AD-3,
 * AD-20, AD-23, AD-33, AD-39 removed): vibefix tracks no task of its own, so nothing live names the Board -- the
 * module {@code vn.vibefix.work}, its tables ({@code work.board}, {@code work.work_item}, their history,
 * {@code work.attachment}, {@code work.work_item_commit}, {@code work.split_deferral}), the Work Item key
 * ({@code WorkItemKey}, {@code work_item_keys}), {@code delivery.handover_item_note} with the notes a handover
 * carried, the retired read scopes ({@code WORK_ITEMS}, {@code WORK_ITEM_NOTES}, {@code ATTACHMENTS}), the refusal
 * {@code WORK_ITEMS_NOT_DONE}, ADM-9's {@code workItemReviews} group, the Board's own routes ({@code /bang},
 * {@code /work-items}) or the Portfolio facts it fed ({@code workItemsApproved}, {@code reviewsWritten}) -- in
 * {@code backend/src/main} outside its Flyway history, or in {@code frontend/src} outside its tests.
 * <p>
 * One name keeps its place on purpose: the URL prefix {@code /api/v1/boards/{boardId}}, which every Job-scoped
 * endpoint of several modules still uses and which names the Job, never a Board. Story 24.2a re-homed JOB-1's
 * progress endpoint under it while the module still stood, and renaming the prefix is no part of this story.
 * {@code screen.admin.reports.kind.WORK_ITEM_NOTE} also keeps its place, as Story 24.1's {@code PAGE} does: it
 * labels the Abuse Reports filed on a Work Item note before this story, which no migration deletes, so ADM-7 still
 * has to name their kind. The kind is gone from {@code AbuseReports.KINDS} and from the SPA's {@code ReportKind},
 * so no new one can carry it.
 */
class RetiredBoardTest {

	static final Path BACKEND_MAIN = RetiredPagesTest.BACKEND_MAIN;

	static final Path FRONTEND_SRC = RetiredPagesTest.FRONTEND_SRC;

	static final Path VI = RetiredPagesTest.VI;

	/** What no live file may say, each with what it names. */
	static final Map<String, Pattern> RETIRED = Map.ofEntries(
			Map.entry("the module", Pattern.compile("\\bvn\\.vibefix\\.work\\b")),
			Map.entry("a Board table", Pattern.compile("\\bwork\\.(?:board|work_item|work_item_history"
					+ "|work_item_commit|attachment|split_deferral|admin_read_grant)\\b")),
			Map.entry("the Work Item key", Pattern.compile("\\b(?:WorkItemKey|work_item_keys|workItemKeys)\\b")),
			Map.entry("a handover's Work Item notes",
					Pattern.compile("\\b(?:handover_item_note|itemNotes|PendingItemNote|HandoverItemNote)\\b")),
			Map.entry("a retired read scope",
					Pattern.compile("\\b(?:WORK_ITEMS|WORK_ITEM_NOTES|ATTACHMENTS)\\b")),
			Map.entry("the retired handover refusal", Pattern.compile("\\bWORK_ITEMS_NOT_DONE\\b")),
			Map.entry("ADM-9's Work Item group", Pattern.compile("\\bworkItemReviews\\b")),
			Map.entry("a retired Portfolio fact",
					Pattern.compile("\\b(?:work_items_approved|workItemsApproved|reviews_written|reviewsWritten)\\b")),
			Map.entry("a Board route", Pattern.compile("/work-items|/work-item-notes|/cong-viec/\\$\\{[A-Za-z.]+}/bang")),
			Map.entry("a Board class", Pattern.compile("\\b(?:BoardController|BoardEvidence|BoardFacts|MilestoneWork"
					+ "|WorkEvidence|WorkFacts|WorkGrants|WorkReads|WorkAccess|WorkCounts|WorkReason|WorkReportable"
					+ "|WorkPersonalData|WorkListeners|WorkMilestones|WorkTextCheck|WorkViews|BoardPage"
					+ "|WorkItemSheet|NoteThread|ReviewDialogs)\\b")),
			Map.entry("the Board's notices and to-dos", Pattern.compile("\\bwork\\.(?:work_item_assigned|review_requested"
					+ "|work_item_approved|work_item_returned|review_work_item|reassign_work_items|fix_returned_item)\\b")),
			Map.entry("the module's refusals", Pattern.compile("reason\\.work\\b")),
			Map.entry("the Board's screens", Pattern.compile("screen\\.(?:board|workItem|attachment|workload)\\b")));

	@Test
	void nothingLiveNamesTheBoardAWorkItemOrARetiredReadScope() {
		List<Path> files = new ArrayList<>(RetiredPagesTest.liveFiles(BACKEND_MAIN));
		files.addAll(RetiredPagesTest.liveFiles(FRONTEND_SRC));

		assertThat(files).as("the files read").hasSizeGreaterThan(500)
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "api", "openapi.json")))
			.anyMatch(file -> file.endsWith(Path.of("frontend", "src", "i18n", "vi.json")));
		assertThat(files.stream().flatMap(file -> violations(file, RetiredPagesTest.read(file)).stream()).toList())
			.isEmpty();
	}

	/** The SPA knows every notice and to-do it may draw, so no retired key reaches a list as its raw self. */
	@Test
	void theCatalogAndTheSpaKeepNoBoardNoticeToDoOrScreen() {
		JsonNode vi = JsonMapper.builder().build().readTree(RetiredPagesTest.read(VI));

		assertThat(vi.path("notification").has("work")).as("the four Work Item notices").isFalse();
		assertThat(vi.path("todo").has("work")).as("the three Work Item to-dos").isFalse();
		assertThat(vi.path("reason").has("work")).as("the module's refusals").isFalse();
		assertThat(vi.path("reason").path("delivery").has("WORK_ITEMS_NOT_DONE")).isFalse();
		for (String screen : List.of("board", "workItem", "attachment", "workload")) {
			assertThat(vi.path("screen").has(screen)).as("screen.%s", screen).isFalse();
		}
		assertThat(vi.at("/screen/admin/reports/kind/WORK_ITEM_NOTE").asString())
			.as("ADM-7 still labels the reports filed before this story")
			.isEqualTo("Ghi chú công việc");
		assertThat(vi.at("/screen/admin/statistics/group").has("acceptedMilestones"))
			.as("FR-62's section over accepted Milestones takes its place").isTrue();
		assertThat(vi.at("/screen/handover/sensitive/title").asString())
			.as("the FR-47 dialog's strings moved out of screen.board with the dialog (Story 24.2a)").isNotEmpty();
		assertThat(vi.at("/screen/progress/externalLink").asString()).isNotEmpty();
	}

	/** Nothing may file a new Abuse Report on a Work Item note, and nothing may hide one. */
	@Test
	void noAbuseReportKindNamesAWorkItemNote() {
		assertThat(RetiredPagesTest.read(javaSource("admin/internal/AbuseReports.java"))).as("AbuseReports.KINDS")
			.doesNotContain("\"WORK_ITEM_NOTE\"");
		assertThat(RetiredPagesTest.read(javaSource("admin/internal/ContentHiding.java"))).as("ContentHiding.KINDS")
			.doesNotContain("\"WORK_ITEM_NOTE\"");
		assertThat(RetiredPagesTest.read(FRONTEND_SRC.resolve(Path.of("features", "abuse", "ReportAbuseMenu.tsx"))))
			.as("the SPA's ReportKind")
			.doesNotContain("WORK_ITEM_NOTE");
	}

	/** AD-20's surviving scopes: the two Chats and one Milestone Split's versions, and nothing else. */
	@Test
	void theReadScopesAreTheTwoChatsAndASplitsVersions() {
		String scopes = RetiredPagesTest.read(javaSource("shared/AdminReadScope.java"));

		assertThat(scopes).contains("JOB_CHAT", "CREW_CHAT", "SPLIT_VERSIONS");
		assertThat(violations(Path.of("AdminReadScope.java"), scopes)).isEmpty();
	}

	@Test
	void theRulesFindEachRetiredNameAndLeaveTheLiveOnesAlone() {
		Path file = Path.of("Example.java");
		for (String text : List.of("import vn.vibefix.work.MilestoneWork;", "DELETE FROM work.board WHERE job_id = :job",
				"SELECT id FROM work.work_item", "INSERT INTO work.work_item_history", "work.attachment",
				"work.split_deferral", "WorkItemKey.mentionedIn(commit.message())", "rs.getArray(\"work_item_keys\")",
				"commit.workItemKeys.map((key) =>", "DROP TABLE delivery.handover_item_note",
				"body.itemNotes() == null", "all(AdminReadScope.WORK_ITEMS)", "AdminReadScope.WORK_ITEM_NOTES",
				"items @> '[{\"scope\": \"ATTACHMENTS\"}]'", "DeliveryReason.WORK_ITEMS_NOT_DONE",
				"grouped(counts, \"workItemReviews\", \"\"\"", "integer(rs, \"work_items_approved\")",
				"entry.reviewsWritten ?? 0", "GET /api/v1/work-items/{workItemId}",
				"`/cong-viec/${jobId}/bang`", "class BoardController {", "t('reason.work.NOTE_EMPTY')",
				"t('screen.board.tab')", "'work.fix_returned_item'", "new NotificationType(\"work.review_requested\"")) {
			assertThat(violations(file, text)).as(text).isNotEmpty();
		}
		for (String text : List.of("/api/v1/boards/{boardId}/handovers", "`/cong-viec/${jobId}/ban-giao`",
				"GRANT SELECT ON chat.message TO ${runtime_role}",
				"import vn.vibefix.repo.HandoverBranches;", "chat.attachment", "ChatAttachmentFiles",
				"t('screen.handover.sensitive.title')", "t('screen.progress.externalLink')",
				"AdminReadScope.CREW_CHAT", "AdminReadScope.SPLIT_VERSIONS", "DeliveryReason.CRITERION_MARK_MISSING",
				"integer(rs, \"commits_authored\")", "entry.commitsAuthored ?? 0",
				"screen.admin.statistics.group.acceptedMilestones", "handoverBranches.workingRepository()",
				"the Crew work on the Product")) {
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

	private static Path javaSource(String path) {
		return BACKEND_MAIN.resolve(Path.of("java", "vn", "vibefix")).resolve(path);
	}

}
