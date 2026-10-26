package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.MentorApproved;
import vn.vibefix.identity.internal.JdbcPersonSignals.Source;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;
import vn.vibefix.timers.internal.DeadlinePoller;

/** FR-74, AD-19, SG-10: ADM-13's queue and matches, the decisions after a step-up, and what follows them. */
@RecordApplicationEvents
class MentorDecisionIT extends IntegrationTest {

	private static final String REACT = "0192b000-0000-7000-8001-000000000004";

	private static final String SCHOOL_EMAIL = "hung.do@hcmut.edu.vn";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private AdministratorBootstrap bootstrap;

	@Autowired
	private JdbcPersonSignals signals;

	@Autowired
	private StoredFiles files;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private IdentityPersonalData personalData;

	private UUID hung;

	private UUID administrator;

	private byte[] secret;

	@BeforeEach
	void aSubmittedApplicationAndAnAdministrator() throws Exception {
		AdministratorBootstrap.Created created = this.bootstrap.createFirst("ha.phuong@vibefix.test", "Hà Phương",
				"mat-khau-du-dai-1".toCharArray());
		this.administrator = created.accountId();
		this.secret = Totp.fromBase32(created.secret());
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		save(this.hung, "do-manh-hung", "3.0").andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(true));
	}

	@Test
	void theQueueListsPendingApplicationsOldestFirstWithTheirMatches() throws Exception {
		this.clock.advance(Duration.ofHours(1));
		UUID later = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		save(later, "someone-else", "3.0").andExpect(status().isOk());
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		IdentityTestSupport.verifyStudent(this.jdbc, student, LocalDate.of(2027, 9, 30));
		this.transactions.executeWithoutResult(status -> {
			this.signals.record(student, Source.SCHOOL_EMAIL, SCHOOL_EMAIL);
			this.signals.record(this.hung, Source.GITHUB_EMAIL, "Hung.Do@hcmut.edu.vn");
		});

		this.mvc.perform(get("/api/v1/admin/mentor-applications").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].accountId").value(this.hung.toString()))
			.andExpect(jsonPath("$.items[0].displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.items[0].matches").value(1))
			.andExpect(jsonPath("$.items[1].accountId").value(later.toString()))
			.andExpect(jsonPath("$.items[1].matches").value(0));
		this.mvc.perform(get("/api/v1/admin/mentor-applications?sort=submittedAt,desc").with(admin()))
			.andExpect(jsonPath("$.items[0].accountId").value(later.toString()));
		this.mvc.perform(get("/api/v1/admin/mentor-applications?sort=displayName").with(admin()))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/admin/mentor-applications?status=APPROVED").with(admin()))
			.andExpect(jsonPath("$.total").value(0));

		String detail = this.mvc.perform(get("/api/v1/admin/mentor-applications/" + this.hung).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.experience").value("Kỹ sư phần mềm 8 năm."))
			.andExpect(jsonPath("$.skills[0].name").value("React"))
			.andExpect(jsonPath("$.privacyNoticeVersion").value("3.0"))
			.andExpect(jsonPath("$.matches.length()").value(1))
			.andExpect(jsonPath("$.matches[0].accountId").value(student.toString()))
			.andExpect(jsonPath("$.matches[0].role").value("STUDENT"))
			.andExpect(jsonPath("$.matches[0].kind").value("EMAIL"))
			.andExpect(jsonPath("$.matches[0].verifiedUntil").value("2027-09-30"))
			.andExpect(jsonPath("$.matches[0].erased").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(detail).as("never the shared value or its HMAC").doesNotContainIgnoringCase(SCHOOL_EMAIL)
			.doesNotContain("hmac");
		this.mvc.perform(get("/api/v1/admin/mentor-applications/" + later).with(admin()))
			.andExpect(jsonPath("$.matches.length()").value(0));
		this.mvc.perform(get("/api/v1/admin/mentor-applications/" + student).with(admin()))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/mentor-applications").with(as(student, Role.STUDENT)))
			.andExpect(status().isNotFound());
	}

	@Test
	void aGoogleAccountThatSharesASignalIsAMatchWithNoRole() throws Exception {
		UUID google = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-hung", "3.0");
		this.transactions.executeWithoutResult(status -> {
			this.signals.record(google, Source.GOOGLE_EMAIL, "hung.do@gmail.test");
			this.signals.record(this.hung, Source.GITHUB_EMAIL, "Hung.Do@gmail.test");
		});

		this.mvc.perform(get("/api/v1/admin/mentor-applications/" + this.hung).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.matches.length()").value(1))
			.andExpect(jsonPath("$.matches[0].accountId").value(google.toString()))
			.andExpect(jsonPath("$.matches[0].role").value(nullValue()))
			.andExpect(jsonPath("$.matches[0].kind").value("EMAIL"));
	}

	@Test
	void anApprovalNeedsTheCodeThenOpensEveryMentorActionAndDeletesTheDocumentAfter30Days() throws Exception {
		upload(this.hung, "chung-chi.pdf", TestFiles.pdf()).andExpect(status().isCreated());
		UUID document = documentId();

		decide("approve", "{\"stepUpCode\":\"" + wrong(code()) + "\"}").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("STEP_UP_FAILED"))
			.andExpect(jsonPath("$.attemptsLeft").value(4));
		assertThat(statusOf(this.hung)).isEqualTo("PENDING");

		decide("approve", "{\"stepUpCode\":\"" + code() + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.decidedAt").exists());

		assertThat(statusOf(this.hung)).isEqualTo("APPROVED");
		assertThat(this.events.stream(MentorApproved.class)).singleElement()
			.satisfies(event -> assertThat(event.accountId()).isEqualTo(this.hung));
		assertThat(notifications(this.hung, MentorDecisions.APPROVED_NOTIFICATION)).isOne();
		assertThat(audited("identity.mentor_approved")).isOne();
		this.mvc.perform(get("/api/v1/profile/mentor").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
			.andExpect(jsonPath("$.decidedAt").exists())
			.andExpect(jsonPath("$.rejectionReason").doesNotExist());
		this.mvc.perform(get("/api/v1/admin/mentor-applications/" + this.hung).with(admin()))
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.document.fileUrl").value("/api/v1/files/" + document));

		this.clock.advance(Duration.ofSeconds(30));
		decide("approve", "{\"stepUpCode\":\"" + code() + "\"}").andExpect(status().isConflict());
		assertThat(this.events.stream(MentorApproved.class)).hasSize(1);

		this.clock.advance(MentorDecisions.DOCUMENT_KEPT.minusMinutes(1));
		this.poller.runDue();
		assertThat(this.files.exists(document)).isTrue();
		this.clock.advance(Duration.ofMinutes(2));
		this.poller.runDue();

		assertThat(this.files.exists(document)).isFalse();
		assertThat(this.jdbc.sql("SELECT document_file_id FROM identity.mentor_application WHERE account_id = :id")
			.param("id", this.hung).query(UUID.class).optional()).isEmpty();
		assertThat(deadline(MentorDocumentDeletion.KEY)).isEqualTo("FIRED");
		assertThat(audited("identity.mentor_document_deleted")).isOne();
	}

	@Test
	void aRejectionCarriesItsReasonAndAWaitBeforeTheMentorMaySubmitAgain() throws Exception {
		decide("reject", "{\"reason\":\" \",\"stepUpCode\":\"" + code() + "\"}").andExpect(status().isBadRequest());
		Instant decidedAt = this.clock.instant();

		decide("reject", "{\"reason\":\"Chưa có thông tin kinh nghiệm cụ thể.\",\"stepUpCode\":\"" + code() + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));

		assertThat(statusOf(this.hung)).isEqualTo("REJECTED");
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'reason' FROM notification.notification WHERE account_id = :id AND type = :type
				""").param("id", this.hung).param("type", MentorDecisions.REJECTED_NOTIFICATION).query(String.class)
			.single()).isEqualTo("Chưa có thông tin kinh nghiệm cụ thể.");
		assertThat(audited("identity.mentor_rejected")).isOne();
		assertThat(dueOf(MentorReapplyWait.KEY)).isEqualTo(decidedAt.plus(MentorDecisions.REAPPLY_WAIT));
		assertThat(dueOf(MentorApplicationPurge.KEY))
			.isEqualTo(decidedAt.atOffset(ZoneOffset.UTC).plusMonths(MentorDecisions.FIELDS_KEPT_MONTHS).toInstant());
		assertThat(this.events.stream(MentorApproved.class)).isEmpty();
		this.mvc.perform(get("/api/v1/profile/mentor").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.approvalStatus").value("REJECTED"))
			.andExpect(jsonPath("$.rejectionReason").value("Chưa có thông tin kinh nghiệm cụ thể."))
			.andExpect(jsonPath("$.reapplyAfter").exists());
		this.mvc.perform(get("/api/v1/admin/mentor-applications?status=REJECTED").with(admin()))
			.andExpect(jsonPath("$.items[0].accountId").value(this.hung.toString()));

		save(this.hung, "do-manh-hung", null).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MENTOR_REAPPLY_TOO_SOON"))
			.andExpect(jsonPath("$.date").value("2026-02-04"));
		upload(this.hung, "cv.pdf", TestFiles.pdf()).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MENTOR_REAPPLY_TOO_SOON"));
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		// The profile stays reserved through the wait.
		save(other, "do-manh-hung", "3.0").andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MENTOR_LINKEDIN_IN_USE"));
	}

	@Test
	void afterTheWaitTheProfileIsFreeAndASaveResubmits() throws Exception {
		reject();
		this.clock.advance(MentorDecisions.REAPPLY_WAIT.plusMinutes(1));
		this.poller.runDue();
		assertThat(deadline(MentorReapplyWait.KEY)).isEqualTo("FIRED");
		assertThat(this.jdbc.sql("SELECT linkedin_reserved FROM identity.mentor_application WHERE account_id = :id")
			.param("id", this.hung).query(Boolean.class).single()).isFalse();

		save(this.hung, "do-manh-hung", null).andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(true));

		assertThat(statusOf(this.hung)).isEqualTo("PENDING");
		assertThat(this.jdbc.sql("""
				SELECT submitted_at = :now AND decision_id IS NULL AND linkedin_reserved
				FROM identity.mentor_application WHERE account_id = :id
				""").param("id", this.hung).param("now", Timestamp.from(this.clock.instant())).query(Boolean.class)
			.single()).isTrue();
		assertThat(notifications(this.administrator, MentorApplications.RECEIVED_NOTIFICATION)).isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment WHERE account_id = :id")
			.param("id", this.hung).query(Long.class).single()).as("the first notice stands").isOne();
		this.mvc.perform(get("/api/v1/profile/mentor").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.approvalStatus").value("PENDING"))
			.andExpect(jsonPath("$.rejectionReason").doesNotExist());

		this.clock.advance(Duration.ofDays(400));
		this.poller.runDue();
		assertThat(deadline(MentorApplicationPurge.KEY)).as("the resubmission replaced its decision").isEqualTo("OBSOLETE");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.mentor_application").query(Long.class).single())
			.isOne();
	}

	@Test
	void aProfileFreedByTheWaitCanGoToAnotherApplication() throws Exception {
		reject();
		this.clock.advance(MentorDecisions.REAPPLY_WAIT.plusMinutes(1));
		this.poller.runDue();
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		save(other, "do-manh-hung", "3.0").andExpect(status().isOk());

		save(this.hung, "do-manh-hung", null).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MENTOR_LINKEDIN_IN_USE"));
		assertThat(statusOf(this.hung)).isEqualTo("REJECTED");
	}

	@Test
	void twelveMonthsAfterARejectionTheFieldsGoAndTheDecisionStays() throws Exception {
		upload(this.hung, "chung-chi.pdf", TestFiles.pdf()).andExpect(status().isCreated());
		UUID document = documentId();
		reject();

		this.clock.advance(Duration.ofDays(366));
		this.poller.runDue();

		assertThat(deadline(MentorApplicationPurge.KEY)).isEqualTo("FIRED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.mentor_application").query(Long.class).single()).isZero();
		assertThat(this.files.exists(document)).isFalse();
		assertThat(this.jdbc.sql("SELECT decision FROM identity.mentor_decision WHERE account_id = :id")
			.param("id", this.hung).query(String.class).single()).isEqualTo("REJECTED");
		assertThat(audited("identity.mentor_application_purged")).isOne();

		// A new application needs the notice again.
		save(this.hung, "do-manh-hung", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PRIVACY_NOTICE_REQUIRED"));
		save(this.hung, "do-manh-hung", "3.0").andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(true));
		assertThat(statusOf(this.hung)).isEqualTo("PENDING");
	}

	@Test
	void onlyASubmittedPendingApplicationCanBeDecided() throws Exception {
		UUID draft = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		this.mvc.perform(post("/api/v1/admin/mentor-applications/" + draft + "/approve").with(admin()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stepUpCode\":\"" + code() + "\"}")).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.mentor_decision").query(Long.class).single()).isZero();
	}

	@Test
	void theDecisionsAreExportedAndErasedWithTheAccount() throws Exception {
		reject();

		assertThat(this.personalData.tables()).contains("identity.mentor_decision");
		assertThat(this.personalData.export(this.hung).orElseThrow().data().get("mentorDecisions")).asList()
			.singleElement()
			.asString()
			.contains("REJECTED", "Chưa có thông tin kinh nghiệm cụ thể.")
			.doesNotContain(this.administrator.toString());
		this.transactions.executeWithoutResult(status -> this.personalData.eraseAccount(this.hung));

		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.mentor_decision").query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.mentor_application").query(Long.class).single()).isZero();
	}

	private void reject() throws Exception {
		decide("reject", "{\"reason\":\"Chưa có thông tin kinh nghiệm cụ thể.\",\"stepUpCode\":\"" + code() + "\"}")
			.andExpect(status().isOk());
	}

	private ResultActions decide(String action, String json) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/mentor-applications/" + this.hung + "/" + action).with(admin())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions save(UUID account, String handle, String notice) throws Exception {
		return this.mvc.perform(put("/api/v1/profile/mentor").with(as(account, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"displayName":"Đỗ Mạnh Hùng","experience":"Kỹ sư phần mềm 8 năm.","yearsOfExperience":8,
					 "skillIds":["%s"],"linkedinUrl":"https://www.linkedin.com/in/%s/","privacyNoticeVersion":%s}
					""".formatted(REACT, handle, notice == null ? "null" : "\"" + notice + "\"")));
	}

	private ResultActions upload(UUID account, String name, byte[] content) throws Exception {
		return this.mvc.perform(multipart("/api/v1/profile/mentor/document")
			.file(new MockMultipartFile("file", name, "application/octet-stream", content))
			.with(as(account, Role.MENTOR))
			.with(csrf()));
	}

	private UUID documentId() {
		return this.jdbc.sql("SELECT document_file_id FROM identity.mentor_application WHERE account_id = :id")
			.param("id", this.hung).query(UUID.class).single();
	}

	private String statusOf(UUID account) {
		return this.jdbc.sql("SELECT mentor_approval_status FROM identity.account WHERE id = :id")
			.param("id", account).query(String.class).single();
	}

	private long notifications(UUID account, String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE account_id = :id AND type = :type")
			.param("id", account).param("type", type).query(Long.class).single();
	}

	private String deadline(String kind) {
		return this.jdbc.sql("SELECT status FROM timers.deadline WHERE kind = :kind")
			.param("kind", kind).query(String.class).single();
	}

	private Instant dueOf(String kind) {
		return this.jdbc.sql("SELECT due_at FROM timers.deadline WHERE kind = :kind")
			.param("kind", kind).query(Timestamp.class).single().toInstant();
	}

	private long audited(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
			.param("action", action).query(Long.class).single();
	}

	private String code() {
		return Totp.codeAt(this.secret, Totp.step(this.clock.instant()));
	}

	private static String wrong(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private RequestPostProcessor admin() {
		return as(this.administrator, Role.ADMINISTRATOR);
	}

}
