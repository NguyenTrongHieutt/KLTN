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
import java.util.List;
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

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;

/** FR-46, FR-47, FR-3, AD-20: the Job Chat from J4, its check, its history, and who reaches it. */
class JobChatIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ChatRedaction redaction;

	private UUID lan;

	private UUID hung;

	private UUID job;

	@BeforeEach
	void aJobThatReachedAgreement() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.jdbc.sql("""
				INSERT INTO identity.customer_profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', 'Lan Nguyễn', 'lan@example.com', now())
				""").param("id", this.lan).update();
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
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("SELECT count(*) FROM chat.chat WHERE job_id = :job AND kind = 'JOB'")
			.param("job", this.job).query(Long.class).single() == 1);
	}

	@Test
	void j4OpensTheChatTellsBothAndLetsTheMentorReadTheContact() throws Exception {
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'chat.chat_opened' AND account_id IN (:lan, :hung)
				""").param("lan", this.lan).param("hung", this.hung).query(Long.class).single() == 2);
		this.mvc.perform(get("/api/v1/customers/" + this.lan + "/contact").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contactEmail").value("lan@example.com"));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unread").value(false));
	}

	@Test
	void theTwoSidesTalkAndNobodyElseFindsTheChat() throws Exception {
		send(this.lan, Role.CUSTOMER, "Chào anh, mình bàn kế hoạch nhé", null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.author").value("Lan Nguyễn"));

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.unread").value(true));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat/messages").with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].mine").value(false))
			.andExpect(jsonPath("$.items[0].body").value("Chào anh, mình bàn kế hoạch nhé"))
			.andExpect(jsonPath("$.hasOlder").value(false));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/chat/read").with(as(this.hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.unread").value(false));
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type LIKE 'chat.message%'")
			.query(Long.class).single()).as("a message sends no notification").isZero();

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		UUID otherMentor = ChatTestSupport.mentorWithFreeStudent(this.jdbc, "Phạm Thu Hà");
		for (RequestPostProcessor outsider : List.of(as(other, Role.CUSTOMER), as(student, Role.STUDENT),
				as(admin, Role.ADMINISTRATOR), as(otherMentor, Role.MENTOR))) {
			this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat/messages").with(outsider)).andExpect(status().isNotFound());
		}
		send(other, Role.CUSTOMER, "Xin chào", null).andExpect(status().isNotFound());
	}

	@Test
	void aLikelyCredentialIsHeldBackUntilTheSenderMasksOrSendsIt() throws Exception {
		String token = fakeToken();
		String text = "Khóa thử của tôi là " + token;

		send(this.lan, Role.CUSTOMER, text, null).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"))
			.andExpect(jsonPath("$.matches[0].kind").value("CREDENTIAL"))
			.andExpect(jsonPath("$.matches[0].masked").value(org.hamcrest.Matchers.containsString("••••••••")));
		assertThat(messages()).as("nothing is stored before the sender decides").isZero();

		String masked = send(this.lan, Role.CUSTOMER, text, "MASK").andExpect(status().isCreated())
			.andExpect(jsonPath("$.maskedCredentials").value(1))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<String>read(masked, "$.body")).startsWith("Khóa thử của tôi là ").doesNotContain(token);
		assertThat(this.jdbc.sql("SELECT count(*) FROM chat.message WHERE body LIKE :token").param("token", "%" + token + "%")
			.query(Long.class).single()).as("no original is kept").isZero();

		send(this.lan, Role.CUSTOMER, text, "SEND").andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(text));
		assertThat(this.jdbc.sql("SELECT count(*) FROM chat.message WHERE sent_despite_warning").query(Long.class).single())
			.isOne();
	}

	/** FR-66 (Story 8.1): J9 or J10 stores masked what was sent despite the warning, keeping no original. */
	@Test
	void aCredentialSentDespiteTheWarningIsStoredMaskedOnceTheJobEnds() throws Exception {
		String token = fakeToken();
		send(this.lan, Role.CUSTOMER, "Khóa thử của tôi là " + token, "SEND").andExpect(status().isCreated());
		send(this.hung, Role.MENTOR, "Cảm ơn, đã nhận.", null).andExpect(status().isCreated());

		assertThat(this.redaction.redact(this.job)).isOne();

		assertThat(this.jdbc.sql("SELECT count(*) FROM chat.message WHERE body LIKE :token").param("token", "%" + token + "%")
			.query(Long.class).single()).as("no original is kept").isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM chat.message
				WHERE sent_despite_warning AND masked_credentials = 1 AND redacted_at IS NOT NULL AND body LIKE 'Khóa thử của tôi là %'
				""").query(Long.class).single()).isOne();
		assertThat(this.redaction.redact(this.job)).as("each message is checked once").isZero();
	}

	@Test
	void theNewestFiftyComeFirstAndOlderOnesOnRequest() throws Exception {
		UUID chat = this.jdbc.sql("SELECT id FROM chat.chat WHERE job_id = :job AND kind = 'JOB'").param("job", this.job).query(UUID.class).single();
		for (int i = 0; i < 60; i++) {
			this.clock.advance(Duration.ofSeconds(1));
			this.jdbc.sql("""
					INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :lan, :body, :now)
					""")
				.param("id", UUID.randomUUID())
				.param("chat", chat)
				.param("lan", this.lan)
				.param("body", "Tin " + i)
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
		}

		String newest = this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat/messages").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.items.length()").value(50))
			.andExpect(jsonPath("$.items[0].body").value("Tin 10"))
			.andExpect(jsonPath("$.items[49].body").value("Tin 59"))
			.andExpect(jsonPath("$.hasOlder").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String oldest = JsonPath.read(newest, "$.items[0].id");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/chat/messages?before=" + oldest).with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.items.length()").value(10))
			.andExpect(jsonPath("$.items[0].body").value("Tin 0"))
			.andExpect(jsonPath("$.hasOlder").value(false));
	}

	@Test
	void anAttachmentIsReadOnlyByTheParticipants() throws Exception {
		String body = this.mvc.perform(multipart("/api/v1/jobs/" + this.job + "/chat/attachments")
			.file(new MockMultipartFile("file", "ke-hoach.pdf", "application/pdf", TestFiles.pdf()))
			.param("body", "Kế hoạch của em")
			.with(as(this.hung, Role.MENTOR))
			.with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachment.name").value("ke-hoach.pdf"))
			.andExpect(jsonPath("$.body").value("Kế hoạch của em"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String file = JsonPath.read(body, "$.attachment.fileId");

		this.mvc.perform(get("/api/v1/files/" + file).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/files/" + file).with(as(other, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(multipart("/api/v1/jobs/" + this.job + "/chat/attachments")
			.file(new MockMultipartFile("file", "trang.svg", "image/svg+xml", TestFiles.svg()))
			.with(as(this.hung, Role.MENTOR))
			.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("FILE_TYPE_NOT_ALLOWED"));
	}

	private ResultActions send(UUID account, Role role, String body, String sensitive) throws Exception {
		String content = "{\"body\":\"" + body + "\"" + (sensitive == null ? "" : ",\"sensitive\":\"" + sensitive + "\"") + "}";
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/chat/messages").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(content));
	}

	private long messages() {
		return this.jdbc.sql("SELECT count(*) FROM chat.message").query(Long.class).single();
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

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
