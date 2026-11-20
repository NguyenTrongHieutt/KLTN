package vn.vibefix.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.scanning.CredentialCheck;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;

/**
 * FR-10, FR-32, AD-4, AD-23, AD-33: the Mentor hands a Funded Assessment Milestone over once each criterion is
 * marked with a note and the report is linked; M2 makes it Submitted on the loaded version, tells the Customer, and
 * moves the to-dos. An Assessment Job's deliverable is the Milestone itself, whose criteria are the Assessment
 * Report's sections (Story 24.1), and the report reaches the Customer as a "Tài liệu" link of the handover, since the
 * platform stores no documents (Story 24.3). A handover carries at most ten links, each {@code https} with a short
 * label, read whole by the Customer, the Mentor and the Crew; only the Mentor hands over.
 */
class HandoverIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private DeliveryListeners listeners;

	/** The credential rules {@code job} still applies (FR-66), which show a stored-as-written text is credential-like. */
	@Autowired
	private CredentialCheck credentials;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID product;

	private UUID job;

	private UUID milestone;

	private UUID criterion;

	/** The preview link a Bug fixing handover carries (Story 8.3); none for an Assessment. */
	private String previewUrl;

	/** Whether the Job is an Assessment, which hands over no code and so names no N-33 version (Story 23.8b). */
	private boolean assessment;

	/** The links "Gửi bàn giao" sends, as JSON; an Assessment's report by default (Story 24.3), none when null. */
	private String links;

	/** The uploads "Gửi bàn giao" attaches, as a JSON array; none when null. */
	private String screenshotIds;

	/** Why a preview is impossible, which "Gửi bàn giao" sends; none when null. */
	private String noPreviewReason;

	/** The {@code sensitive} field a client built before Story 24.4b sends; none when null. */
	private String legacySensitive;

	/** An Assessment Job's report, which the Mentor keeps in a tool of their own (FR-10, Story 24.3). */
	private static final String REPORT = link("DOCUMENT", "https://docs.google.com/document/d/bao-cao-danh-gia",
			"Báo cáo đánh giá sổ đặt bàn");

	@Test
	void theMentorHandsOverAnAssessmentMilestoneWithItsCriteriaMarked() throws Exception {
		world("ASSESSMENT");

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/preflight").param("milestoneId", this.milestone.toString())
			.with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.problems").isEmpty())
			.andExpect(jsonPath("$.version").value(5))
			.andExpect(jsonPath("$.criteria[0].statement").value("Báo cáo nêu đủ chín mục"))
			.andExpect(jsonPath("$.workItems").doesNotExist())
			.andExpect(jsonPath("$.report").doesNotExist());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.waiting.number").value(1))
			.andExpect(jsonPath("$.handovers").isEmpty());

		// Story 24.2: the Work Item gate is gone, so an unmarked criterion is the whole of FR-32's enforcement (R42).
		handOver(this.hung, Role.MENTOR, 5, " ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("CRITERION_MARK_MISSING"));
		handOver(this.hung, Role.MENTOR, 4, "Đạt, đã kiểm tra.")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		handOver(this.vy, Role.STUDENT, 5, "Đạt.").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		handOver(this.lan, Role.CUSTOMER, 5, "Đạt.").andExpect(status().isForbidden());
		// Story 24.3 (FR-10): the report reaches the Customer as a "Tài liệu" link, so a handover without one is refused,
		// and a recording is not a report.
		this.links = null;
		handOver(this.hung, Role.MENTOR, 5, "Đạt, đã kiểm tra.").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("REPORT_LINK_MISSING"));
		this.links = "[" + link("VIDEO", "https://www.loom.com/share/bao-cao", "Video trình bày") + "]";
		handOver(this.hung, Role.MENTOR, 5, "Đạt, đã kiểm tra.").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("REPORT_LINK_MISSING"));
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();
		assertThat(count("SELECT count(*) FROM delivery.handover_link")).isZero();

		// Story 23.8a: "Gửi bàn giao" answers 202 with the submission; an Assessment Job's is done at once.
		String first = upload();
		String second = upload();
		this.links = "[" + REPORT + "]";
		this.screenshotIds = "[\"" + first + "\",\"" + second + "\"]";
		String handover = JsonPath.read(handOver(this.hung, Role.MENTOR, 5, "Đạt, đã kiểm tra.")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("DONE"))
			.andExpect(jsonPath("$.number").value(1))
			.andReturn().getResponse().getContentAsString(), "$.handoverId");
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.round").value(1))
			.andExpect(jsonPath("$.criteria[0].met").value(true))
			.andExpect(jsonPath("$.criteria[0].note").value("Đạt, đã kiểm tra."))
			.andExpect(jsonPath("$.criteria[0].checkSteps").value("Mở báo cáo, đếm mục"))
			.andExpect(jsonPath("$.itemNotes").doesNotExist())
			.andExpect(jsonPath("$.code").doesNotExist())
			.andExpect(jsonPath("$.report").doesNotExist())
			.andExpect(jsonPath("$.links.length()").value(1))
			.andExpect(jsonPath("$.screenshots.length()").value(2));
		// FR-25, R28 (Story 23.8b): an Assessment Job hands over no code, so N-33 is neither asked for nor recorded.
		assertThat(count("""
				SELECT count(*) FROM delivery.handover WHERE team_code_notice_version IS NULL
				    AND team_code_confirmed_at IS NULL AND team_code_account_id IS NULL
				""")).isOne();
		assertThat(this.jdbc.sql("SELECT state || ':' || handover_id FROM job.milestone WHERE id = :id")
			.param("id", this.milestone).query(String.class).single()).isEqualTo("SUBMITTED:" + handover);
		assertThat(count("SELECT version FROM job.job WHERE id = '" + this.job + "'")).isEqualTo(6);
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.milestone_submitted' AND account_id = '"
				+ this.lan + "'")).isOne();
		assertThat(openTodos("delivery.review_handover", this.lan)).isOne();
		assertThat(openTodos("delivery.hand_over", this.hung)).isZero();
		assertThat(this.jdbc.sql("SELECT deadline_at FROM notification.todo WHERE todo_key = 'delivery.review_handover'")
			.query(Timestamp.class).single().toInstant()).isEqualTo(this.clock.instant().plus(Duration.ofDays(7)));

		// The Customer's report: the criteria, the report's link and the images, and no commit (FR-10, FR-32).
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.report").doesNotExist())
			.andExpect(jsonPath("$.criteria[0].note").value("Đạt, đã kiểm tra."))
			.andExpect(jsonPath("$.criteria[0].checkSteps").value("Mở báo cáo, đếm mục"))
			.andExpect(jsonPath("$.itemNotes").doesNotExist())
			.andExpect(jsonPath("$.mentorName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.code").doesNotExist())
			.andExpect(jsonPath("$.links[0].kind").value("DOCUMENT"))
			.andExpect(jsonPath("$.links[0].label").value("Báo cáo đánh giá sổ đặt bàn"))
			.andExpect(jsonPath("$.links[0].url").value("https://docs.google.com/document/d/bao-cao-danh-gia"))
			.andExpect(jsonPath("$.links[0].domain").value("docs.google.com"))
			.andExpect(jsonPath("$.screenshots[*].fileId").value(Matchers.contains(first, second)));
		// Every audience reads the same Report: the criteria with the Mentor's marks and notes (Story 24.2) and its links.
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.criteria[0].note").value("Đạt, đã kiểm tra."))
			.andExpect(jsonPath("$.links[0].url").value("https://docs.google.com/document/d/bao-cao-danh-gia"));
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(stranger, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.waiting").doesNotExist())
			.andExpect(jsonPath("$.handovers[0].id").value(handover));
		handOver(this.hung, Role.MENTOR, 7, "Đạt.").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("MILESTONE_NOT_HELD"));
	}

	/**
	 * Story 24.3 (D3, FR-32): at most ten links, each {@code https}, of a known kind, with a label of 1 to 80
	 * characters stored as written. The eleventh is refused by the rule that names it, and a malformed link by the
	 * request's own validation or by the URL's parse -- one whose host is not the one a reader would see, or which
	 * the table's own check would refuse after a Code Job's copy started; nothing is written either way. Ten links
	 * reach the report in the Mentor's order, and the label and the URL are stored as written (D5).
	 */
	@Test
	void aHandoverTakesTenWellFormedLinksInTheMentorsOrder() throws Exception {
		world("ASSESSMENT");
		List<String> eleven = new ArrayList<>();
		for (int number = 1; number <= 11; number++) {
			eleven.add(link(number == 1 ? "DOCUMENT" : "VIDEO", "https://docs.example.test/lien-ket-" + number,
					"Liên kết " + number));
		}
		this.links = "[" + String.join(",", eleven) + "]";
		handOver(this.hung, Role.MENTOR, 5, "Đạt.").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("TOO_MANY_LINKS"));
		for (String bad : List.of(link("DOCUMENT", "http://docs.example.test/bao-cao", "Báo cáo"),
				link("DOCUMENT", "https://docs.example.test/bao-cao", " "),
				link("DOCUMENT", "https://docs.example.test/bao-cao", "a".repeat(81)),
				link("AUDIO", "https://docs.example.test/bao-cao", "Báo cáo"),
				"{\"kind\":\"DOCUMENT\",\"label\":\"Báo cáo\"}",
				// A browser opens evil.example; a hand-split authority would show docs.google.com.
				link("DOCUMENT", "https://evil.example\\\\@docs.google.com/bao-cao", "Báo cáo"),
				// User info hides the real host from a reader; built at run time, so no credential shape sits here.
				link("DOCUMENT", "https://mentor" + ":" + "khoa@docs.example.test/bao-cao", "Báo cáo"),
				// Java's \S takes an ideographic space and NEL; the table's CHECK and the URI parse do not.
				link("DOCUMENT", "https://docs.example.test/bao\u3000cao", "Báo cáo"),
				link("DOCUMENT", "https://docs.example.test/bao\u0085cao", "Báo cáo"),
				// No host a reader can be shown: a name with an underscore parses as no host at all.
				link("DOCUMENT", "https://my_host.example/bao-cao", "Báo cáo"))) {
			this.links = "[" + REPORT + "," + bad + "]";
			handOver(this.hung, Role.MENTOR, 5, "Đạt.").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"))
				.andExpect(jsonPath("$.reason").doesNotExist());
		}
		assertThat(count("SELECT count(*) FROM delivery.handover")).as("nothing was written").isZero();

		// D5: the label and the URL are stored as written, a token-shaped text included; the label is the limit's own
		// 80 characters. The text is built at run time, so no credential shape sits in this source.
		String token = fakeToken();
		String label = "Biên bản " + "a".repeat(80 - 9 - 1 - token.length()) + " " + token;
		String url = "https://docs.example.test/bien-ban/" + token;
		assertThat(label).hasSize(80);
		assertThat(this.credentials.looksLikeCredential(label)).as("the label looks like a credential").isTrue();
		assertThat(this.credentials.looksLikeCredential(url)).as("and so does the URL").isTrue();
		List<String> ten = new ArrayList<>(eleven.subList(0, 8));
		ten.add(link("VIDEO", "https://xn--bcher-kva.example/video", "Video thao tác"));
		ten.add(link("DOCUMENT", url, label));
		this.links = "[" + String.join(",", ten) + "]";
		String handover = JsonPath.read(handOver(this.hung, Role.MENTOR, 5, "Đạt.").andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString(), "$.handoverId");
		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.links.length()").value(10))
			.andExpect(jsonPath("$.links[0].label").value("Liên kết 1"))
			.andExpect(jsonPath("$.links[1].kind").value("VIDEO"))
			.andExpect(jsonPath("$.links[7].url").value("https://docs.example.test/lien-ket-8"))
			// AD-35: the host as typed, in ASCII, never the Unicode look-alike it encodes.
			.andExpect(jsonPath("$.links[8].domain").value("xn--bcher-kva.example"))
			.andExpect(jsonPath("$.links[9].label").value(label))
			.andExpect(jsonPath("$.links[9].url").value(url))
			.andExpect(jsonPath("$.links[9].domain").value("docs.example.test"));
		assertThat(this.jdbc.sql("SELECT label || ' ' || url FROM delivery.handover_link WHERE position = 9")
			.query(String.class).single()).as("stored unmasked").isEqualTo(label + " " + url);
		assertThat(this.jdbc.sql("SELECT string_agg(position::text, ',' ORDER BY position) FROM delivery.handover_link")
			.query(String.class).single()).isEqualTo("0,1,2,3,4,5,6,7,8,9");
	}

	/**
	 * Story 24.3b (FR-53, AD-20, AD-31): an Administrator holds no role in the Job, so its readers refuse them a handover's
	 * image, and only then does {@code delivery} ask {@link vn.vibefix.delivery.HandoverEvidenceReaders}, for an image a
	 * handover carries and never for an upload no handover took. The assignee of a Dispute on the Job reads it, audited
	 * by the case's module; any other Administrator is refused as anyone is, with 404. Nothing is granted for it, so the
	 * case ending ends it, and the Job's own readers are untouched.
	 */
	@Test
	void anAdministratorReadsAHandoversImageOnlyThroughTheCaseThatShowsIt() throws Exception {
		world("ASSESSMENT");
		String attached = upload();
		this.screenshotIds = "[\"" + attached + "\"]";
		handOver(this.hung, Role.MENTOR, 5, "Đạt, đã kiểm tra.").andExpect(status().isAccepted());
		String pending = upload();
		UUID assignee = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		file(attached, assignee, Role.ADMINISTRATOR).andExpect(status().isNotFound());

		UUID dispute = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at, response_days, statement_due_at, assigned_admin_id, assigned_at, grant_id)
				VALUES (:id, :job, :product, 'CUSTOMER', :lan, 'Báo cáo chưa đạt', 'HELD_MILESTONE', :milestone, 'ACTIVE', 'OPEN',
				    :now, 5, :due, :admin, :now, :grant)
				""").param("id", dispute).param("job", this.job).param("product", this.product).param("lan", this.lan)
			.param("milestone", this.milestone).param("now", now)
			.param("due", Timestamp.from(this.clock.instant().plus(Duration.ofDays(5)))).param("admin", assignee)
			.param("grant", UUID.randomUUID()).update();

		file(attached, assignee, Role.ADMINISTRATOR).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object f ON f.entry_id = e.id"
				+ " WHERE e.action = 'dispute.evidence_viewed.handover_image' AND e.actor_account_id = '" + assignee
				+ "' AND e.job_id = '" + this.job + "' AND f.object_type = 'files.stored_file' AND f.object_id = '" + attached
				+ "'")).isOne();
		file(pending, assignee, Role.ADMINISTRATOR).andExpect(status().isNotFound());
		file(attached, other, Role.ADMINISTRATOR).andExpect(status().isNotFound());
		file(attached, this.lan, Role.CUSTOMER).andExpect(status().isOk());
		file(attached, this.vy, Role.STUDENT).andExpect(status().isOk());
		file(pending, this.hung, Role.MENTOR).andExpect(status().isOk());
		file(pending, this.lan, Role.CUSTOMER).andExpect(status().isNotFound());
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action LIKE '%.handover_image'"))
			.as("only the read the case let through").isOne();

		// The case moves to another Administrator: nothing was granted, so the first reads nothing more.
		this.jdbc.sql("UPDATE dispute.dispute SET assigned_admin_id = :other WHERE id = :id").param("other", other)
			.param("id", dispute).update();
		file(attached, assignee, Role.ADMINISTRATOR).andExpect(status().isNotFound());
		file(attached, other, Role.ADMINISTRATOR).andExpect(status().isOk());
	}

	/**
	 * Story 24.4b (D5): nothing checks, masks or refuses the Mentor's text. A criterion note and a no-preview reason
	 * holding a credential-like value are stored, and read back through the Delivery Report, exactly as typed, with no
	 * warning; the {@code sensitive} field an older client still sends is ignored.
	 */
	@Test
	void theNotesAndTheNoPreviewReasonAreStoredAsWritten() throws Exception {
		world("ASSESSMENT");
		String note = "Khóa thử " + fakeToken() + " vẫn nằm trong ghi chú.";
		String reason = "Chỉ chạy trên máy của quán, với khóa " + stripeTestKey() + ".";
		assertThat(this.credentials.looksLikeCredential(note)).as("the note looks like a credential").isTrue();
		assertThat(this.credentials.looksLikeCredential(reason)).as("and so does the reason").isTrue();
		this.links = "[" + REPORT + "," + link("VIDEO", "https://www.loom.com/share/bao-cao", "Video trình bày") + "]";
		this.noPreviewReason = reason;
		this.legacySensitive = "MASK";

		String handover = JsonPath.read(handOver(this.hung, Role.MENTOR, 5, note).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("DONE"))
			.andReturn().getResponse().getContentAsString(), "$.handoverId");

		this.mvc.perform(get("/api/v1/handovers/" + handover).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.criteria[0].note").value(note))
			.andExpect(jsonPath("$.preview.noPreviewReason").value(reason));
		assertThat(this.jdbc.sql("SELECT note FROM delivery.handover_criterion WHERE handover_id = :id")
			.param("id", UUID.fromString(handover)).query(String.class).single()).isEqualTo(note);
		assertThat(this.jdbc.sql("SELECT no_preview_reason FROM delivery.handover WHERE id = :id")
			.param("id", UUID.fromString(handover)).query(String.class).single()).isEqualTo(reason);
	}

	@Test
	void aCodeJobWaitsForItsHandoverCommit() throws Exception {
		world("BUG_FIX");
		this.previewUrl = "https://so-dat-ban.vercel.app";
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/handovers/preflight").param("milestoneId", this.milestone.toString())
			.with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.problems").value(Matchers.hasItem("HANDOVER_COMMIT_MISSING")));
		handOver(this.hung, Role.MENTOR, 5, "Đạt.").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("HANDOVER_COMMIT_MISSING"));
		assertThat(count("SELECT count(*) FROM delivery.handover")).isZero();
	}

	@Test
	void aFundedMilestoneOpensTheHandoverToDoDueAtTheEndOfItsDueDate() {
		world("ASSESSMENT");
		this.listeners.onJobTransitioned(new JobTransitioned(UUID.randomUUID(), UUID.randomUUID(), this.job, "M1", "SYSTEM",
				"PLANNED", "FUNDED", 6, 1, Map.of("milestoneId", this.milestone.toString(), "number", "1")));
		await().atMost(Duration.ofSeconds(10)).until(() -> openTodos("delivery.hand_over", this.hung) == 1);
		assertThat(this.jdbc.sql("SELECT deadline_at FROM notification.todo WHERE todo_key = 'delivery.hand_over'")
			.query(Timestamp.class).single().toInstant()).isEqualTo(Instant.parse("2026-11-20T17:00:00Z"));
	}

	/**
	 * An Active Job of {@code type} with Milestone 1 Funded and two Work Items on it: the seeded Story,
	 * approved, and a Task in progress. Vy is an active Crew member.
	 */
	private void world(String type) {
		this.assessment = "ASSESSMENT".equals(type);
		this.links = this.assessment ? "[" + REPORT + "]" : null;
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TestPeople.REACT), "React");
		this.vy = TestPeople.student(this.jdbc, "Trần Thảo Vy", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID product = UUID.randomUUID();
		this.product = product;
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Báo cáo đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		this.job = UUID.randomUUID();
		this.milestone = UUID.randomUUID();
		this.criterion = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[:type],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("type", type)
			.param("mentor", this.hung).param("application", UUID.randomUUID()).param("now", now).update();
		String content = """
				{"scope":"Đánh giá","priceVnd":1500000,"codeJob":%s,"assessment":%s,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Báo cáo","priceVnd":1500000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Báo cáo nêu đủ chín mục","checkSteps":"Mở báo cáo, đếm mục"}]}]}
				""".formatted(!"ASSESSMENT".equals(type), "ASSESSMENT".equals(type), this.milestone, this.criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", this.job).param("content", content).param("mentor", this.hung).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at)
				VALUES (:id, :job, 0, 'Báo cáo', 1500000, 2, now(), 'FUNDED', now())
				""").param("id", this.milestone).param("job", this.job).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("student", this.vy)
			.param("signed", Timestamp.from(this.clock.instant().minus(Duration.ofMinutes(5)))).update();
	}

	private ResultActions handOver(UUID account, Role role, long version, String criterionNote) throws Exception {
		String json = "{\"milestoneId\":\"" + this.milestone + "\",\"version\":" + version + ",\"criteria\":[{\"criterionId\":\""
				+ this.criterion + "\",\"met\":true,\"note\":\"" + criterionNote + "\"}]"
				// Story 24.4b: an older client's field, which the server ignores.
				+ (this.legacySensitive == null ? "" : ",\"sensitive\":\"" + this.legacySensitive + "\"")
				+ (this.noPreviewReason == null ? "" : ",\"noPreviewReason\":\"" + this.noPreviewReason + "\"")
				// N-33 (FR-25, R28, Story 23.8b): a Code Job's handover names the version in force; an Assessment's none.
				+ (this.assessment ? "" : ",\"teamCodeNoticeVersion\":\"1.0\"")
				+ (this.previewUrl == null ? "" : ",\"previewUrl\":\"" + this.previewUrl + "\"")
				+ (this.links == null ? "" : ",\"links\":" + this.links)
				+ (this.screenshotIds == null ? "" : ",\"screenshotIds\":" + this.screenshotIds) + "}";
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/handovers").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	/** One link of "Gửi bàn giao", as the form sends it. */
	private static String link(String kind, String url, String label) {
		return "{\"kind\":\"" + kind + "\",\"url\":\"" + url + "\",\"label\":\"" + label + "\"}";
	}

	/** An image the Mentor uploads for the next handover (Story 8.3). */
	private String upload() throws Exception {
		byte[] png;
		try (var in = getClass().getResourceAsStream("/authz/bodies/upload.png")) {
			png = java.util.Objects.requireNonNull(in).readAllBytes();
		}
		return JsonPath.read(this.mvc.perform(multipart("/api/v1/boards/" + this.job + "/handovers/screenshots")
			.file(new MockMultipartFile("file", "bao-cao.png", "image/png", png)).with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.fileId");
	}

	private ResultActions file(String fileId, UUID account, Role role) throws Exception {
		return this.mvc.perform(get("/api/v1/files/" + fileId).with(as(account, role)));
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private long openTodos(String key, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE todo_key = :key AND account_id = :id AND closed_at IS NULL")
			.param("key", key).param("id", account).query(Long.class).single();
	}

	/** A GitHub token shape built at run time, so no credential-shaped literal sits in the source. */
	private static String fakeToken() {
		String alphabet = "Q7mZ2xK9pL4vB8nR3tY6wC1dF5gH0jSaEuIoNcXbVk";
		StringBuilder token = new StringBuilder("ghp").append('_');
		for (int i = 0; i < 36; i++) {
			token.append(alphabet.charAt((i * 7 + 3) % alphabet.length()));
		}
		return token.toString();
	}

	/** A Stripe test key shape built at run time, for the same reason. */
	private static String stripeTestKey() {
		return "sk" + "_test_" + "ab".repeat(12);
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
