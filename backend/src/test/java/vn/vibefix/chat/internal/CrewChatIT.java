package vn.vibefix.chat.internal;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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

import com.jayway.jsonpath.JsonPath;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-77, AD-20, AD-41: the Crew Chat opens with the Job Chat at J4; the Mentor and the active Crew
 * talk, a former member reads only their window, nobody else (the Customer included) finds it, an
 * Administrator reads it only under a grant naming it, and J9 or J10 makes both Chats read-only.
 */
class CrewChatIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ChatGrants grants;

	@Autowired
	private ChatListeners listeners;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private UUID crewChat;

	@BeforeEach
	void aJobThatReachedAgreement() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID product = ChatTestSupport.product(this.jdbc, this.lan);
		this.job = ChatTestSupport.job(this.jdbc, product, this.lan, "OPEN", null, this.clock.instant());
		this.hung = ChatTestSupport.mentorWithFreeStudent(this.jdbc, "Đỗ Mạnh Hùng");
		UUID application = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :lan, :hung, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", application)
			.param("job", this.job)
			.param("product", product)
			.param("lan", this.lan)
			.param("hung", this.hung)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/applications/" + application + "/select")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/nda").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ndaVersion\":\"1.0\",\"commitEmailNoticeAcknowledged\":true}")).andExpect(status().isNoContent());
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("SELECT count(*) FROM chat.chat WHERE job_id = :job")
			.param("job", this.job).query(Long.class).single() == 2);
		this.crewChat = this.jdbc.sql("SELECT id FROM chat.chat WHERE job_id = :job AND kind = 'CREW'")
			.param("job", this.job)
			.query(UUID.class)
			.single();
	}

	@Test
	void j4OpensTheCrewChatForTheMentorWithoutTellingTheCustomer() throws Exception {
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew-chat").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.chatId").value(this.crewChat.toString()))
			.andExpect(jsonPath("$.title").value("Sửa lỗi đặt bàn"))
			.andExpect(jsonPath("$.unread").value(false))
			.andExpect(jsonPath("$.readOnly").value(false));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew-chat").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		// Only the Job Chat's opening is told, to the Customer and the Mentor.
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM notification.notification WHERE type = 'chat.chat_opened'").query(Long.class).single() == 2);
	}

	@Test
	void theMentorAndTheActiveCrewTalkAndNobodyElseFindsTheCrewChat() throws Exception {
		UUID vy = student("Trần Thảo Vy");
		crewRow(vy, "ACTIVE", this.clock.instant(), null);
		UUID assigned = student("Lê Minh Tùng");
		crewRow(assigned, "ASSIGNED", null, null);

		sendCrew(this.hung, Role.MENTOR, "Chào cả nhóm").andExpect(status().isCreated())
			.andExpect(jsonPath("$.author").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$.mine").value(true));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew-chat").with(as(vy, Role.STUDENT)))
			.andExpect(jsonPath("$.unread").value(true))
			.andExpect(jsonPath("$.readOnly").value(false));
		sendCrew(vy, Role.STUDENT, "Em chào anh").andExpect(status().isCreated())
			.andExpect(jsonPath("$.author").value("Trần Thảo Vy"));
		this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[1].author").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.items[1].mine").value(false));
		this.mvc.perform(post("/api/v1/crew-chats/" + this.crewChat + "/read").with(as(vy, Role.STUDENT)).with(csrf()))
			.andExpect(status().isNoContent());

		UUID otherStudent = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		UUID otherMentor = ChatTestSupport.mentorWithFreeStudent(this.jdbc, "Phạm Thu Hà");
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		for (RequestPostProcessor outsider : List.of(as(this.lan, Role.CUSTOMER), as(assigned, Role.STUDENT),
				as(otherStudent, Role.STUDENT), as(otherMentor, Role.MENTOR), as(admin, Role.ADMINISTRATOR))) {
			this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(outsider))
				.andExpect(status().isNotFound());
			this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew-chat").with(outsider)).andExpect(status().isNotFound());
		}
		sendCrew(this.lan, Role.CUSTOMER, "Cho tôi xem").andExpect(status().isNotFound());
		sendCrew(assigned, Role.STUDENT, "Em vào được chưa").andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat/messages").with(as(vy, Role.STUDENT)))
			.andExpect(status().isNotFound());
		UUID jobChat = this.jdbc.sql("SELECT id FROM chat.chat WHERE job_id = :job AND kind = 'JOB'")
			.param("job", this.job)
			.query(UUID.class)
			.single();
		// The Job Chat is no Crew Chat, even for its own Customer.
		this.mvc.perform(get("/api/v1/crew-chats/" + jobChat + "/messages").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	@Test
	void anInactiveOrFormerMemberReadsOnlyTheirWindowAndWritesNothing() throws Exception {
		UUID vy = student("Trần Thảo Vy");
		Instant joined = this.clock.instant();
		crewRow(vy, "ACTIVE", joined, null);
		sendCrew(this.hung, Role.MENTOR, "Trong lúc Vy còn trong nhóm").andExpect(status().isCreated());
		this.clock.advance(Duration.ofHours(1));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', window_end = :now, released_at = :now, released_reason = 'REMOVED'
				WHERE student_account_id = :vy
				""").param("now", Timestamp.from(this.clock.instant())).param("vy", vy).update();
		this.clock.advance(Duration.ofHours(1));
		String after = this.mvc.perform(multipart("/api/v1/crew-chats/" + this.crewChat + "/attachments")
			.file(new MockMultipartFile("file", "sau-khi-roi.pdf", "application/pdf", TestFiles.pdf()))
			.param("body", "Sau khi Vy rời nhóm")
			.with(as(this.hung, Role.MENTOR))
			.with(csrf()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String fileAfter = JsonPath.read(after, "$.attachment.fileId");

		this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].body").value("Trong lúc Vy còn trong nhóm"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/crew-chat").with(as(vy, Role.STUDENT)))
			.andExpect(jsonPath("$.readOnly").value(true))
			.andExpect(jsonPath("$.unread").value(false));
		this.mvc.perform(get("/api/v1/files/" + fileAfter).with(as(vy, Role.STUDENT))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + fileAfter).with(as(this.hung, Role.MENTOR))).andExpect(status().isOk());
		sendCrew(vy, Role.STUDENT, "Em quay lại").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("CHAT_READ_ONLY"));

		UUID minh = student("Nguyễn Văn Minh");
		crewRow(minh, "INACTIVE", joined, null);
		// A window with no end is never read as open.
		this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(minh, Role.STUDENT)))
			.andExpect(status().isNotFound());
	}

	@Test
	void anAdministratorReadsTheCrewChatOnlyUnderAGrantNamingIt() throws Exception {
		sendCrew(this.hung, Role.MENTOR, "Chào cả nhóm").andExpect(status().isCreated());
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.grants.grantAdminRead(UUID.randomUUID(), admin, this.job,
				Set.of(new AdminReadItem(AdminReadScope.JOB_CHAT, null, null)), UUID.randomUUID());
		// A Job Chat grant reads no Crew Chat.
		this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(admin, Role.ADMINISTRATOR)))
			.andExpect(status().isNotFound());

		this.grants.grantAdminRead(UUID.randomUUID(), admin, this.job,
				Set.of(new AdminReadItem(AdminReadScope.CREW_CHAT, null, this.hung)), UUID.randomUUID());
		this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(admin, Role.ADMINISTRATOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].mine").value(false));
		sendCrew(admin, Role.ADMINISTRATOR, "Quản trị viên").andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'chat.admin_read' AND actor_account_id = :admin
				""").param("admin", admin).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void j9OrJ10MakesBothChatsReadOnlyOnce() throws Exception {
		UUID event = UUID.randomUUID();
		this.listeners.onJobTransitioned(transition(event, "J10"));
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM chat.chat WHERE job_id = :job AND closed_at IS NOT NULL
				""").param("job", this.job).query(Long.class).single() == 2);
		Instant closed = closedAt();

		this.clock.advance(Duration.ofHours(1));
		this.listeners.onJobTransitioned(transition(event, "J10"));
		this.listeners.onJobTransitioned(transition(UUID.randomUUID(), "J9"));
		// A redelivery or a later close changes nothing.
		await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> closedAt().equals(closed));

		sendCrew(this.hung, Role.MENTOR, "Còn ai không").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("CHAT_READ_ONLY"));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/chat/messages").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Cảm ơn anh\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("CHAT_READ_ONLY"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.readOnly").value(true));
		this.mvc.perform(get("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk());
	}

	private Instant closedAt() {
		return this.jdbc.sql("SELECT max(closed_at) FROM chat.chat WHERE job_id = :job")
			.param("job", this.job)
			.query(Timestamp.class)
			.single()
			.toInstant();
	}

	private JobTransitioned transition(UUID eventId, String transition) {
		return new JobTransitioned(eventId, UUID.randomUUID(), this.job, transition, "SYSTEM", "ACTIVE", "CANCELLED", 9,
				1, Map.of());
	}

	private UUID student(String name) {
		return TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
	}

	private void crewRow(UUID student, String status, @Nullable Instant signed, @Nullable Instant windowEnd) {
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, window_end, created_at)
				VALUES (:id, :job, :student, :status, :signed, :signed, :end, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("job", this.job)
			.param("student", student)
			.param("status", status)
			.param("signed", signed == null ? null : Timestamp.from(signed.minus(Duration.ofMinutes(5))))
			.param("end", windowEnd == null ? null : Timestamp.from(windowEnd))
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private ResultActions sendCrew(UUID account, Role role, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/crew-chats/" + this.crewChat + "/messages").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
