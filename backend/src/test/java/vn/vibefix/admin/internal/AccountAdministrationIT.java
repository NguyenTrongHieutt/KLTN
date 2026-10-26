package vn.vibefix.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * ADM-2 (FR-5, FR-59, FR-78, SM-8): the search ignores accents; a lock ends the account's sessions,
 * publishes {@link AccountLocked}, and is recorded and audited, an unlock restores only the sign-in; a
 * Mentor's private feedback is read under audit; and nothing approves or revokes anyone (Story 19.7),
 * while the old revocations stay readable in the history.
 */
@RecordApplicationEvents
class AccountAdministrationIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private StudentVerification verification;

	@Test
	void theSearchIgnoresAccentsAndALockEndsTheSessionsUntilTheUnlock() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		this.mvc.perform(get("/api/v1/admin/users").param("q", "thao vy").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(vy.toString()))
			.andExpect(jsonPath("$.items[0].status").value("VERIFIED"));
		// No Mentor has an approval status any more (Story 19.7).
		this.mvc.perform(get("/api/v1/admin/users").param("role", "MENTOR").with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(hung.toString()))
			.andExpect(jsonPath("$.items[0].status").doesNotExist());
		this.mvc.perform(get("/api/v1/admin/users").param("status", "APPROVED").with(admin(ha)))
			.andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/v1/admin/users").with(admin(ha)))
			.andExpect(jsonPath("$.items[?(@.role == 'ADMINISTRATOR')]").isEmpty());

		storeSession(vy, Role.STUDENT);
		assertThat(sessionsOf(vy)).isEqualTo(1);
		command(ha, vy, "lock", "{\"reason\":\"Gửi tin nhắn quấy rối\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.lockedAt").isNotEmpty())
			.andExpect(jsonPath("$.lockReason").value("Gửi tin nhắn quấy rối"))
			.andExpect(jsonPath("$.history[0].action").value("LOCKED"))
			.andExpect(jsonPath("$.history[0].actedBy").value("Hà Phương"));
		assertThat(sessionsOf(vy)).isZero();
		assertThat(this.events.stream(AccountLocked.class).filter(event -> event.accountId().equals(vy)).count())
			.isEqualTo(1);
		command(ha, vy, "lock", "{\"reason\":\"Lần nữa\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_LOCKED"));
		this.mvc.perform(get("/api/v1/admin/users").param("status", "LOCKED").with(admin(ha)))
			.andExpect(jsonPath("$.total").value(1));

		// Rows of one MutableClock instant tie on acted_at; the unlock comes later.
		this.clock.advance(Duration.ofMinutes(5));
		command(ha, vy, "unlock", "{\"reason\":\"Đã xác minh lại\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.lockedAt").doesNotExist())
			.andExpect(jsonPath("$.history.length()").value(2))
			.andExpect(jsonPath("$.history[0].action").value("UNLOCKED"));
		command(ha, vy, "unlock", "{\"reason\":\"Lần nữa\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_LOCKED"));
		assertThat(audits("identity.account_locked")).isEqualTo(1);
		assertThat(audits("identity.account_unlocked")).isEqualTo(1);

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		command(ha, other, "lock", "{\"reason\":\"Thử\"}").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("ADMINISTRATOR_ACCOUNT"));
		awaitListeners();
	}

	@Test
	void aGoogleAccountWithNoRoleIsListedFoundByItsGoogleEmailOpenedAndLocked() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.0");

		this.mvc.perform(get("/api/v1/admin/users").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + lan + "')].name").value("Người dùng google-lan"));
		this.mvc.perform(get("/api/v1/admin/users").param("q", "google-lan@gmail.test").with(admin(ha)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(lan.toString()))
			.andExpect(jsonPath("$.items[0].role").doesNotExist())
			.andExpect(jsonPath("$.items[0].status").doesNotExist());
		this.mvc.perform(get("/api/v1/admin/users/" + lan).with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Người dùng google-lan"))
			.andExpect(jsonPath("$.email").value("google-lan@gmail.test"))
			.andExpect(jsonPath("$.role").doesNotExist());

		command(ha, lan, "lock", "{\"reason\":\"Gửi tin nhắn quấy rối\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.lockReason").value("Gửi tin nhắn quấy rối"));
		assertThat(this.events.stream(AccountLocked.class).filter(event -> event.accountId().equals(lan)))
			.singleElement()
			.satisfies(event -> assertThat(event.role()).isNull());
		awaitListeners();
	}

	@Test
	void theRevocationsAreGoneAndTheOldOnesStayReadableInTheHistory() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID vy = TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");

		command(ha, vy, "revoke-verification", "{\"reason\":\"Thẻ sinh viên giả\"}").andExpect(status().isNotFound());
		command(ha, hung, "revoke-approval", "{\"ground\":\"OTHER\",\"reason\":\"Vi phạm\",\"stepUpCode\":\""
				+ AdministratorTestSupport.code(ha, this.clock.instant()) + "\"}")
			.andExpect(status().isNotFound());
		assertThat(this.verification.isVerified(vy)).isTrue();

		// An Administrator's revocation from before Story 19.7 stays in ADM-2's history (AD-8).
		this.jdbc.sql("""
				INSERT INTO identity.account_action (id, account_id, action, reason, ground, acted_by, acted_at)
				VALUES (:id, :account, 'APPROVAL_REVOKED', 'Thiếu tôn trọng với sinh viên', 'PRIVATE_FEEDBACK', :admin, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("account", hung)
			.param("admin", ha.accountId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.mvc.perform(get("/api/v1/admin/users/" + hung).with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.approval").doesNotExist())
			.andExpect(jsonPath("$.history[0].action").value("APPROVAL_REVOKED"))
			.andExpect(jsonPath("$.history[0].ground").value("PRIVATE_FEEDBACK"))
			.andExpect(jsonPath("$.history[0].actedBy").value("Hà Phương"));
	}

	/** Story 19.4: any person's account may hold a student verification; ended rows count for nothing. */
	@Test
	void theVerifiedFilterAndTheStatusReadEveryPersonsLiveValidRow() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.0");
		IdentityTestSupport.verifyStudent(this.jdbc, lan, LocalDate.parse("2026-09-30"));
		UUID unlinked = TeamTestSupport.student(this.jdbc, "Đã bỏ", true, List.of(), "", LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				UPDATE identity.verification SET ended_at = now(), ended_reason = 'UNLINKED', facts = NULL WHERE account_id = :id
				""").param("id", unlinked).update();

		this.mvc.perform(get("/api/v1/admin/users").param("status", "VERIFIED").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[?(@.id == '" + lan + "')].status").value("VERIFIED"));
		this.mvc.perform(get("/api/v1/admin/users").with(admin(ha)))
			.andExpect(jsonPath("$.items[?(@.id == '" + unlinked + "')].status").value("UNVERIFIED"));
		this.mvc.perform(get("/api/v1/admin/users/" + lan).with(admin(ha)))
			.andExpect(jsonPath("$.verification.method").value("SCHOOL_EMAIL"))
			.andExpect(jsonPath("$.verification.current").value(true));
		this.mvc.perform(get("/api/v1/admin/users/" + unlinked).with(admin(ha)))
			.andExpect(jsonPath("$.verification").doesNotExist());
	}

	@Test
	void aMentorsPrivateFeedbackIsReadUnderAuditAndTheLowOnesAreListed() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID vy = UUID.randomUUID();
		UUID minh = UUID.randomUUID();
		UUID first = window(hung);
		UUID second = window(hung);
		feedback(first, hung, vy, 2, "Hay quát nạt");
		feedback(second, hung, vy, 1, null);
		this.mvc.perform(get("/api/v1/admin/users/" + hung).with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.feedback.count").value(2))
			.andExpect(jsonPath("$.feedback.comments[0]").value("Hay quát nạt"))
			.andExpect(jsonPath("$.feedback.enoughToAct").value(false));
		assertThat(audits("admin.private_feedback_viewed")).isEqualTo(1);

		feedback(second, hung, minh, 2, "Không trả lời nhóm");
		this.mvc.perform(get("/api/v1/admin/overview").with(admin(ha))).andExpect(jsonPath("$.lowFeedback.count").value(1));
		this.mvc.perform(get("/api/v1/admin/users").param("status", "LOW_FEEDBACK").with(admin(ha)))
			.andExpect(jsonPath("$.items[0].id").value(hung.toString()));
		this.mvc.perform(get("/api/v1/admin/users/" + hung).with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.feedback.count").value(3))
			.andExpect(jsonPath("$.feedback.enoughToAct").value(true));
		assertThat(audits("admin.private_feedback_viewed")).isEqualTo(2);
	}

	private UUID window(UUID mentor) {
		UUID job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at)
				VALUES (:job, :product, :customer, :mentor, 'Sổ đặt bàn', :now, :ends)
				""")
			.param("job", job)
			.param("product", UUID.randomUUID())
			.param("customer", UUID.randomUUID())
			.param("mentor", mentor)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("ends", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14))))
			.update();
		return job;
	}

	private void feedback(UUID job, UUID mentor, UUID student, int stars, String comment) {
		this.jdbc.sql("""
				INSERT INTO reputation.private_feedback (id, job_id, mentor_account_id, student_account_id, stars, comment,
				    created_at, delete_after)
				VALUES (:id, :job, :mentor, :student, :stars, :comment, :now, :later)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("mentor", mentor)
			.param("student", student)
			.param("stars", stars)
			.param("comment", comment)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("later", Timestamp.from(this.clock.instant().plus(Duration.ofDays(730))))
			.update();
	}

	/** A stored session of the account, as a browser sign-in leaves it. */
	@SuppressWarnings("unchecked")
	private void storeSession(UUID account, Role role) {
		SessionRepository<Session> repository = (SessionRepository<Session>) this.sessions;
		Session session = repository.createSession();
		SecurityContext security = SecurityContextHolder.createEmptyContext();
		security.setAuthentication(IdentityTestSupport.authentication(account, role));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
		repository.save(session);
	}

	private long sessionsOf(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM platform.spring_session WHERE principal_name = :name")
			.param("name", account.toString())
			.query(Long.class)
			.single();
	}

	private long audits(String action) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = :action")
			.param("action", action)
			.query(Long.class)
			.single();
	}

	/** The consequences run after commit; let them finish before the database is reset. */
	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	private ResultActions command(Administrator administrator, UUID account, String action, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/admin/users/" + account + "/" + action).with(admin(administrator))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
