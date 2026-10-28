package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 16.1 (FR-69, FR-60, AD-20, AD-34): users report what they can see, at most 10 a day; ADM-6 queues the
 * reports; taking a report on a chat message or a Work Item note opens a grant on that one item, a takeover
 * ends it at once, and the decision 30 days later; the assignee hides through FR-60, records an action, or
 * dismisses, and the reporter hears the outcome.
 */
class AbuseReportIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	private HeldCrewJob world;

	private Administrator ha;

	/** A second Administrator, without a step-up secret: only the first is enrolled through the bootstrap. */
	private UUID quan;

	private UUID post;

	private UUID crewMessage;

	private UUID otherMessage;

	private UUID note;

	private UUID otherNote;

	@BeforeEach
	void aRunningJobWithItsRecordsAnOpenJobPostAndTwoAdministrators() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.quan = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, 'quan.le@vibefix.test', 'Lê Quân', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", this.quan).update();
		Timestamp at = Timestamp.from(this.clock.instant().minus(Duration.ofDays(1)));
		this.post = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Thêm thanh toán QR', 'Chưa có', 'Có QR', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :at, :lapses, 1, :at, :at)
				""").param("id", this.post).param("product", this.world.product()).param("owner", this.world.lan())
			.param("at", at).param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(10)))).update();
		UUID chat = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, 'CREW', :at)")
			.param("id", chat).param("job", this.world.job()).param("product", this.world.product()).param("at", at).update();
		this.crewMessage = message(chat, "Lời lẽ xúc phạm trong nhóm", at);
		this.otherMessage = message(chat, "Tin nhắn khác của nhóm", at);
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', 2, :at)
				ON CONFLICT DO NOTHING
				""").param("job", this.world.job()).param("product", this.world.product()).param("at", at).update();
		UUID item = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, created_at, updated_at)
				VALUES (:id, :job, 1, 'TASK', 'Sửa trang đặt bàn', 'Mô tả', :at, :at)
				""").param("id", item).param("job", this.world.job()).param("at", at).update();
		this.note = note(item, "Ghi chú bị báo cáo", at);
		this.otherNote = note(item, "Ghi chú khác", at);
	}

	@Test
	void aMentorReportsAJobPostAndTheAssigneeHidesItThroughFr60() throws Exception {
		file(this.world.hung(), Role.MENTOR, "JOB_POST", this.post).andExpect(status().isCreated());
		file(this.world.hung(), Role.MENTOR, "JOB_POST", this.post).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_REPORTED"));
		// Whoever reads "Tìm việc" may report what it shows, whatever role they hold elsewhere (Story 20.3); without
		// GitHub linked nobody reads it, so nobody reports it.
		IdentityTestSupport.unlinkGitHub(this.jdbc, this.world.vy());
		file(this.world.vy(), Role.STUDENT, "JOB_POST", this.post).andExpect(status().isNotFound());
		file(this.ha.accountId(), Role.ADMINISTRATOR, "JOB_POST", this.post).andExpect(status().isNotFound());
		// Nobody reports their own content: the Job Post's Customer reads it on "Tìm việc" but cannot report it.
		file(this.world.lan(), Role.CUSTOMER, "JOB_POST", this.post).andExpect(status().isNotFound());
		file(this.world.lan(), Role.CUSTOMER, "PRODUCT", this.world.product()).andExpect(status().isNotFound());
		file(this.world.hung(), Role.MENTOR, "PRODUCT", this.world.product()).andExpect(status().isCreated());

		this.mvc.perform(get("/api/v1/admin/abuse-reports").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.kind == 'JOB_POST')].label").value("Thêm thanh toán QR"))
			.andExpect(jsonPath("$[?(@.kind == 'JOB_POST')].reporterName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$[?(@.kind == 'JOB_POST')].status").value("OPEN"))
			.andExpect(jsonPath("$[?(@.kind == 'PRODUCT')].label").value("Sổ đặt bàn Quán Mây"));
		UUID report = reportOf("JOB_POST");
		decide(this.ha.accountId(), report, "HIDE", "Tin tuyển lừa đảo").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_ASSIGNED"));
		take(this.ha.accountId(), report).andExpect(jsonPath("$.hideable").value(true))
			.andExpect(jsonPath("$.itemReadable").value(false));
		decide(this.ha.accountId(), report, "HIDE", "Tin tuyển lừa đảo").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("HIDDEN"))
			.andExpect(jsonPath("$.outcome").value("Đã ẩn tin tuyển."));

		assertThat(count("SELECT count(*) FROM job.job WHERE id = '" + this.post + "' AND hidden_at IS NOT NULL")).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'admin.content_hidden' AND account_id = '"
				+ this.world.lan() + "'")).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'admin.abuse_report_resolved' AND account_id = '"
				+ this.world.hung() + "'")).isOne();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'admin.abuse_report_decided'")).isOne();
		decide(this.ha.accountId(), report, "DISMISS", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_DECIDED"));
	}

	@Test
	void aChatReportGrantsTheAssigneeThatOneMessageUntilThirtyDaysAfterTheDecision() throws Exception {
		file(this.world.lan(), Role.CUSTOMER, "CREW_CHAT_MESSAGE", this.crewMessage).andExpect(status().isNotFound());
		file(this.world.vy(), Role.STUDENT, "CREW_CHAT_MESSAGE", this.crewMessage).andExpect(status().isCreated());
		UUID report = reportOf("CREW_CHAT_MESSAGE");
		this.mvc.perform(get("/api/v1/admin/abuse-reports").with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Lời lẽ xúc phạm"))));
		message(this.ha.accountId(), this.crewMessage).andExpect(status().isNotFound());

		take(this.ha.accountId(), report).andExpect(jsonPath("$.itemReadable").value(true))
			.andExpect(jsonPath("$.hideable").value(false));
		message(this.ha.accountId(), this.crewMessage).andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("Lời lẽ xúc phạm trong nhóm"));
		message(this.ha.accountId(), this.otherMessage).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/crew-chat/messages").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'chat.admin_read' AND actor_account_id = '"
				+ this.ha.accountId() + "'")).isOne();

		take(this.quan, report);
		message(this.ha.accountId(), this.crewMessage).andExpect(status().isNotFound());
		message(this.quan, this.crewMessage).andExpect(status().isOk());
		decide(this.quan, report, "HIDE", "Ẩn đi").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("NOT_HIDEABLE"));
		decide(this.quan, report, "ACTION_TAKEN", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTION_TAKEN"));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'admin.abuse_report_resolved' AND account_id = '"
				+ this.world.vy() + "'")).isOne();

		this.clock.advance(Duration.ofDays(29));
		message(this.quan, this.crewMessage).andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(2));
		message(this.quan, this.crewMessage).andExpect(status().isNotFound());
	}

	@Test
	void aWorkItemNoteReportOpensThatNoteOnly() throws Exception {
		file(this.world.minh(), Role.STUDENT, "WORK_ITEM_NOTE", this.note).andExpect(status().isCreated());
		file(this.world.lan(), Role.CUSTOMER, "WORK_ITEM_NOTE", this.otherNote).andExpect(status().isNotFound());
		UUID report = reportOf("WORK_ITEM_NOTE");
		this.mvc.perform(get("/api/v1/admin/abuse-reports/" + report).with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.label").value("SDB-1 Sửa trang đặt bàn"))
			.andExpect(content().string(not(containsString("Ghi chú bị báo cáo"))));
		take(this.ha.accountId(), report);

		this.mvc.perform(get("/api/v1/work-item-notes/" + this.note).with(admin(this.ha.accountId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.text").value("Ghi chú bị báo cáo"))
			.andExpect(jsonPath("$.author").value("Trần Thảo Vy"));
		this.mvc.perform(get("/api/v1/work-item-notes/" + this.otherNote).with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.world.job() + "/work-items").with(admin(this.ha.accountId())))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/work-item-notes/" + this.note).with(as(this.world.minh(), Role.STUDENT)))
			.andExpect(status().isOk());
	}

	@Test
	void ratingsAndPublicProfilesAreReportableAndTheDailyLimitHolds() throws Exception {
		UUID rating = this.world.rating(this.jdbc, this.world.vy(), this.clock.instant().minus(Duration.ofDays(2)));
		file(this.world.minh(), Role.STUDENT, "RATING", rating).andExpect(status().isCreated());
		file(this.world.vy(), Role.STUDENT, "USER", this.world.hung()).andExpect(status().isCreated());
		file(this.world.vy(), Role.STUDENT, "USER", this.world.vy()).andExpect(status().isNotFound());
		String minh = this.jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id")
			.param("id", this.world.minh())
			.query(String.class)
			.single();
		this.mvc.perform(post("/api/v1/abuse-reports").with(as(this.world.hung(), Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"USER\",\"targetLogin\":\"" + minh + "\",\"reason\":\"Mạo danh\"}"))
			.andExpect(status().isCreated());
		assertThat(count("SELECT count(*) FROM admin.abuse_report WHERE kind = 'USER' AND target_id = '" + this.world.minh() + "'"))
			.isOne();
		this.mvc.perform(post("/api/v1/abuse-reports").with(as(this.world.hung(), Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"RATING\",\"targetLogin\":\"" + minh + "\",\"reason\":\"Sai loại\"}"))
			.andExpect(status().isBadRequest());
		file(this.world.vy(), Role.STUDENT, "PAGE", UUID.randomUUID()).andExpect(status().isNotFound());

		for (int i = 0; i < 9; i++) {
			this.jdbc.sql("""
					INSERT INTO admin.abuse_report (id, reporter_account_id, kind, target_id, label, reason, status, created_at)
					VALUES (:id, :reporter, 'USER', :target, 'Ai đó', 'Spam', 'OPEN', :now)
					""").param("id", UUID.randomUUID()).param("reporter", this.world.vy()).param("target", UUID.randomUUID())
				.param("now", Timestamp.from(this.clock.instant())).update();
		}
		file(this.world.vy(), Role.STUDENT, "CREW_CHAT_MESSAGE", this.otherMessage).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.reason").value("ABUSE_REPORT_LIMIT"))
			.andExpect(header().exists("Retry-After"));
		this.clock.advance(Duration.ofDays(1));
		file(this.world.vy(), Role.STUDENT, "CREW_CHAT_MESSAGE", this.otherMessage).andExpect(status().isCreated());
	}

	private ResultActions file(UUID account, Role role, String kind, UUID target) throws Exception {
		return this.mvc.perform(post("/api/v1/abuse-reports").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"" + kind + "\",\"targetId\":\"" + target + "\",\"reason\":\"Vi phạm quy định\"}"));
	}

	private ResultActions take(UUID admin, UUID report) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/abuse-reports/" + report + "/take").with(admin(admin)).with(csrf()))
			.andExpect(status().isOk());
	}

	private ResultActions decide(UUID admin, UUID report, String action, String hideReason) throws Exception {
		String hide = hideReason == null ? "" : ",\"hideReason\":\"" + hideReason + "\"";
		return this.mvc.perform(post("/api/v1/admin/abuse-reports/" + report + "/decision").with(admin(admin)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"" + action + "\",\"outcome\":\"Đã ẩn tin tuyển.\"" + hide + "}"));
	}

	private ResultActions message(UUID admin, UUID message) throws Exception {
		return this.mvc.perform(get("/api/v1/chat-messages/" + message).with(admin(admin)));
	}

	private UUID reportOf(String kind) {
		return this.jdbc.sql("SELECT id FROM admin.abuse_report WHERE kind = :kind").param("kind", kind).query(UUID.class).single();
	}

	private UUID message(UUID chat, String body, Timestamp at) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :author, :body, :at)")
			.param("id", id).param("chat", chat).param("author", this.world.hung()).param("body", body).param("at", at).update();
		return id;
	}

	private UUID note(UUID item, String text, Timestamp at) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :author, 'NOTE', '{}'::jsonb, :text, :at)
				""").param("id", id).param("item", item).param("job", this.world.job()).param("author", this.world.vy())
			.param("text", text).param("at", at).update();
		return id;
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private RequestPostProcessor admin(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.ADMINISTRATOR, this.clock.instant());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
