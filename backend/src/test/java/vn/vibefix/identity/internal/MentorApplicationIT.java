package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;

/** FR-74, AD-19: the Mentor application on MEN-5, its submission, its LinkedIn reservation, and its document. */
class MentorApplicationIT extends IntegrationTest {

	private static final String REACT = "0192b000-0000-7000-8001-000000000004";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private AdministratorBootstrap bootstrap;

	@Autowired
	private Todos todos;

	@Autowired
	private StoredFiles files;

	@Autowired
	private TransactionTemplate transactions;

	private UUID hung;

	private UUID administrator;

	@BeforeEach
	void aPendingMentorAndAnAdministrator() {
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		this.transactions.executeWithoutResult(status -> this.todos.open(new TodoRequest(
				SignIns.COMPLETE_MENTOR_APPLICATION, this.hung, Audience.SUBJECT, this.hung, Map.of(), "/ho-so", null, null)));
		this.administrator = this.bootstrap.createFirst("ha.phuong@vibefix.test", "Hà Phương",
				"mat-khau-du-dai-1".toCharArray()).accountId();
	}

	@Test
	void theFirstSaveSubmitsAndTellsTheAdministrators() throws Exception {
		this.mvc.perform(get("/api/v1/profile").with(mentor(this.hung)))
			.andExpect(jsonPath("$.mentor.approvalStatus").value("PENDING"))
			.andExpect(jsonPath("$.mentor.submittedAt").doesNotExist());

		save(this.hung, "do-manh-hung", "2.0").andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(true));

		this.mvc.perform(get("/api/v1/profile").with(mentor(this.hung)))
			.andExpect(jsonPath("$.mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.mentor.skills[0].name").value("React"))
			.andExpect(jsonPath("$.mentor.submittedAt").exists());
		assertThat(openTodos(this.hung)).isZero();
		assertThat(this.jdbc.sql("SELECT type FROM notification.notification WHERE account_id = :id")
			.param("id", this.administrator)
			.query(String.class)
			.list()).contains(MentorApplications.RECEIVED_NOTIFICATION);
		assertThat(audited("identity.mentor_application_submitted")).isOne();
		assertThat(this.jdbc.sql("SELECT notice_version FROM identity.acknowledgment WHERE account_id = :id")
			.param("id", this.hung)
			.query(String.class)
			.list()).containsExactly("2.0");
		this.mvc.perform(get("/api/v1/admin/overview").with(admin()))
			.andExpect(jsonPath("$.mentorApplications.count").value(1));
	}

	@Test
	void theNoticeMustBeAcceptedOnceAndLaterSavesOnlyEdit() throws Exception {
		save(this.hung, "do-manh-hung", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PRIVACY_NOTICE_REQUIRED"));
		save(this.hung, "do-manh-hung", "1.0").andExpect(status().isBadRequest());
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.mentor_application").query(Long.class).single()).isZero();

		save(this.hung, "do-manh-hung", "2.0").andExpect(jsonPath("$.submitted").value(true));
		String submittedAt = this.jdbc.sql("SELECT submitted_at::text FROM identity.mentor_application")
			.query(String.class)
			.single();
		this.clock.advance(Duration.ofHours(2));
		save(this.hung, "do-manh-hung-2", null).andExpect(status().isOk()).andExpect(jsonPath("$.submitted").value(false));

		assertThat(this.jdbc.sql("SELECT submitted_at::text FROM identity.mentor_application").query(String.class).single())
			.isEqualTo(submittedAt);
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification").query(Long.class).single()).isOne();
		assertThat(audited("identity.mentor_application_updated")).isOne();
	}

	@Test
	void aLinkedInProfileServesOneLiveApplication() throws Exception {
		save(this.hung, "do-manh-hung", "2.0").andExpect(status().isOk());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);

		save(other, "Do-Manh-Hung", "2.0").andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("MENTOR_LINKEDIN_IN_USE"));
		save(other, "someone-else", "2.0").andExpect(status().isOk());
	}

	@Test
	void theDocumentIsReadByAdministratorsOnlyAndReplacedInPlace() throws Exception {
		upload(this.hung, "chung-chi.pdf", TestFiles.pdf()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("chung-chi.pdf"));
		UUID first = documentId();
		this.mvc.perform(get("/api/v1/profile").with(mentor(this.hung)))
			.andExpect(jsonPath("$.mentor.document.name").value("chung-chi.pdf"))
			.andExpect(jsonPath("$.mentor.submittedAt").doesNotExist());

		this.mvc.perform(get("/api/v1/files/" + first).with(mentor(this.hung))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + first).with(admin())).andExpect(status().isOk());
		assertThat(audited("identity.mentor_document_viewed")).isOne();

		upload(this.hung, "cv.png", TestFiles.png(6, 6)).andExpect(status().isCreated());
		assertThat(this.files.exists(first)).as("the earlier document is deleted").isFalse();
		save(this.hung, "do-manh-hung", "2.0").andExpect(jsonPath("$.submitted").value(true));
		UUID second = documentId();

		this.mvc.perform(delete("/api/v1/profile/mentor/document").with(mentor(this.hung)).with(csrf()))
			.andExpect(status().isNoContent());
		assertThat(this.files.exists(second)).isFalse();
		this.mvc.perform(delete("/api/v1/profile/mentor/document").with(mentor(this.hung)).with(csrf()))
			.andExpect(status().isNoContent());
		upload(this.hung, "the.zip", TestFiles.zip()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("FILE_TYPE_NOT_ALLOWED"));
	}

	@Test
	void anotherRoleFindsNothing() throws Exception {
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);

		this.mvc.perform(put("/api/v1/profile/mentor").with(mentor(student)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("do-manh-hung", "2.0"))).andExpect(status().isNotFound());
	}

	private ResultActions save(UUID account, String handle, String notice) throws Exception {
		return this.mvc.perform(put("/api/v1/profile/mentor").with(mentor(account)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(handle, notice)));
	}

	private static String body(String handle, String notice) {
		return """
				{"displayName":"Đỗ Mạnh Hùng","experience":"Kỹ sư phần mềm 8 năm.","yearsOfExperience":8,
				 "skillIds":["%s"],"linkedinUrl":"https://www.linkedin.com/in/%s/","otherLink":"https://github.com/hungdm",
				 "privacyNoticeVersion":%s}
				""".formatted(REACT, handle, notice == null ? "null" : "\"" + notice + "\"");
	}

	private ResultActions upload(UUID account, String name, byte[] content) throws Exception {
		return this.mvc.perform(multipart("/api/v1/profile/mentor/document")
			.file(new MockMultipartFile("file", name, "application/octet-stream", content))
			.with(mentor(account))
			.with(csrf()));
	}

	private UUID documentId() {
		return this.jdbc.sql("SELECT document_file_id FROM identity.mentor_application WHERE account_id = :id")
			.param("id", this.hung)
			.query(UUID.class)
			.single();
	}

	private long openTodos(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.todo WHERE account_id = :id AND closed_at IS NULL")
			.param("id", account)
			.query(Long.class)
			.single();
	}

	private long audited(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
			.param("action", action)
			.query(Long.class)
			.single();
	}

	private RequestPostProcessor mentor(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account,
				this.jdbc.sql("SELECT role FROM identity.account WHERE id = :id").param("id", account)
					.query(String.class).single().equals("STUDENT") ? Role.STUDENT : Role.MENTOR,
				this.clock.instant());
	}

	private RequestPostProcessor admin() {
		return IdentityTestSupport.signedIn(this.sessions, this.administrator, Role.ADMINISTRATOR, this.clock.instant());
	}

}
