package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.StudentVerification;
import vn.vibefix.notification.internal.RecordingMailSender;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * FR-5, AD-7, AD-42, UX-DR16, UX-DR27: the reminder 14 days before 30 September, the expiry, which ends the
 * row {@code EXPIRED} and deletes its facts, and renewal, which asks for the address only and keeps the
 * declared name and major.
 */
class VerificationExpiryIT extends IntegrationTest {

	private static final Pattern CODE = Pattern.compile("Mã xác minh vibefix của bạn: ([0-9]{6}) .*");

	private static final String MINH = "minh@sis.hust.edu.vn";

	private static final String NAME = "Trần Quang Minh";

	private static final String MAJOR = "Khoa học máy tính";

	/** 2026-10-19 10:00 in Vietnam. */
	private static final Instant VERIFIED = Instant.parse("2026-10-19T03:00:00Z");

	/** 2027-09-17 00:00 in Vietnam: 14 days before the expiry. */
	private static final Instant REMINDER = Instant.parse("2027-09-16T17:00:00Z");

	/** 2027-10-01 00:00 in Vietnam: the end of 30 September. */
	private static final Instant EXPIRY = Instant.parse("2027-09-30T17:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private StudentVerification studentVerification;

	@Autowired
	private Deadlines timers;

	@Autowired
	private TransactionTemplate transactions;

	private UUID student;

	@BeforeEach
	void aVerifiedStudent() throws Exception {
		this.student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		this.jdbc.sql("UPDATE identity.account SET github_email = 'minh@users.github.test' WHERE id = :id")
			.param("id", this.student)
			.update();
		this.clock.setInstant(VERIFIED);
		verify(MINH).andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"));
		this.mail.reset();
	}

	@Test
	void verifyingSchedulesTheReminderAndTheExpiry() {
		assertThat(deadlines()).containsExactly("identity.verification_reminder PENDING " + REMINDER,
				"identity.verification_expiry PENDING " + EXPIRY);
	}

	@Test
	void fourteenDaysBeforeTheExpiryTheStudentIsRemindedInAppAndByEmail() {
		this.clock.setInstant(REMINDER.minusSeconds(1));
		assertThat(this.poller.runDue()).isZero();

		this.clock.setInstant(REMINDER);
		assertThat(this.poller.runDue()).isOne();

		assertThat(this.jdbc.sql("""
				SELECT params ->> 'date' FROM notification.notification
				WHERE account_id = :id AND type = 'identity.verification_expiring'
				""").param("id", this.student).query(String.class).single()).isEqualTo("30/09/2027");
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).singleElement().satisfies(sent -> {
			assertThat(sent.to()).isEqualTo("minh@users.github.test");
			assertThat(sent.subject()).isEqualTo("Xác minh sinh viên hết hạn ngày 30/09/2027");
		});
		assertThat(this.studentVerification.isVerified(this.student)).isTrue();
	}

	@Test
	void atTheExpiryTheRowEndsItsFactsGoAndThePersonIsTold() throws Exception {
		this.clock.setInstant(EXPIRY);
		assertThat(this.poller.runDue()).as("the reminder, then the expiry").isEqualTo(2);

		assertThat(this.studentVerification.isVerified(this.student)).isFalse();
		assertThat(this.jdbc.sql("""
				SELECT ended_reason || ' ' || (facts IS NULL) || ' ' || (subject_hmac IS NOT NULL)
				FROM identity.verification WHERE account_id = :id
				""").param("id", this.student).query(String.class).single()).isEqualTo("EXPIRED true true");
		assertThat(this.jdbc.sql("SELECT ended_at FROM identity.verification WHERE account_id = :id")
			.param("id", this.student).query(java.sql.Timestamp.class).single()).isEqualTo(java.sql.Timestamp.from(EXPIRY));
		assertThat(this.jdbc.sql("""
				SELECT coalesce(student_name, '-') || coalesce(student_school, '-') || coalesce(student_major, '-')
				FROM identity.profile WHERE account_id = :id
				""").param("id", this.student).query(String.class).single()).as("the projection is cleared").isEqualTo("---");
		assertThat(this.jdbc.sql("""
				SELECT type FROM notification.notification WHERE account_id = :id ORDER BY created_at, type
				""").param("id", this.student).query(String.class).list())
			.contains("identity.verification_expired", "identity.verification_expiring");
		// No account role marks a Student to ask again (Story 20.3): the student verification is optional.
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE account_id = :id AND todo_key = 'identity.verify_student' AND closed_at IS NULL
				""").param("id", this.student).query(Long.class).single()).isZero();
		// The reminder and the expiry lead to ACC-2, where the verification lives (Story 19.3).
		assertThat(this.jdbc.sql("""
				SELECT link FROM notification.notification WHERE account_id = :id
				UNION ALL SELECT link FROM notification.todo WHERE account_id = :id AND todo_key = 'identity.verify_student'
				""").param("id", this.student).query(String.class).list()).hasSize(2).containsOnly("/ho-so/xac-minh");
		assertThat(this.jdbc.sql("""
				SELECT actor_type FROM audit.audit_entry WHERE action = 'identity.verification_expired'
				""").query(String.class).single()).isEqualTo("SYSTEM");
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).extracting(RecordingMailSender.Sent::subject)
			.contains("Xác minh sinh viên đã hết hạn");
		view().andExpect(jsonPath("$.status").value("EXPIRED"))
			.andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"))
			.andExpect(jsonPath("$.school").doesNotExist())
			.andExpect(jsonPath("$.name").doesNotExist());
		assertThat(deadlines()).containsExactly("identity.verification_reminder FIRED", "identity.verification_expiry FIRED");
	}

	@Test
	void renewingInTheLastFourteenDaysCountsForTheNextSchoolYearAndObsoletesTheOldDeadlines() throws Exception {
		this.clock.setInstant(Instant.parse("2027-09-20T03:00:00Z"));
		view().andExpect(jsonPath("$.status").value("VERIFIED"))
			.andExpect(jsonPath("$.renewalOpen").value(true))
			.andExpect(jsonPath("$.pendingCode").doesNotExist());

		// The renewal asks for the address only: a name or a major sent with it is ignored.
		verify("minh.k23@vnu.edu.vn", "Tên khác", "Ngành khác").andExpect(jsonPath("$.verifiedUntil").value("2028-09-30"))
			.andExpect(jsonPath("$.school").value("vnu.edu.vn"))
			.andExpect(jsonPath("$.name").value(NAME))
			.andExpect(jsonPath("$.major").value(MAJOR))
			.andExpect(jsonPath("$.renewalOpen").value(false));
		assertThat(this.jdbc.sql("""
				SELECT coalesce(ended_reason, 'LIVE') || ' ' || (facts IS NULL) FROM identity.verification
				WHERE account_id = :id ORDER BY verified_at
				""").param("id", this.student).query(String.class).list()).containsExactly("REPLACED true", "LIVE false");
		assertThat(this.jdbc.sql("SELECT student_school || ' ' || student_name FROM identity.profile WHERE account_id = :id")
			.param("id", this.student).query(String.class).single()).isEqualTo("vnu.edu.vn " + NAME);

		assertThat(this.poller.runDue()).as("the old reminder").isOne();
		this.clock.setInstant(EXPIRY);
		assertThat(this.poller.runDue()).as("the old expiry").isOne();
		assertThat(this.studentVerification.isVerified(this.student)).isTrue();
		assertThat(deadlines()).containsExactly("identity.verification_reminder OBSOLETE",
				"identity.verification_expiry OBSOLETE",
				"identity.verification_reminder PENDING " + Instant.parse("2028-09-16T17:00:00Z"),
				"identity.verification_expiry PENDING " + Instant.parse("2028-09-30T17:00:00Z"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE account_id = :id")
			.param("id", this.student).query(Long.class).single()).as("nothing told about the old verification").isZero();
	}

	@Test
	void renewalOpensOnlyFourteenDaysBeforeTheExpiry() throws Exception {
		this.clock.setInstant(Instant.parse("2027-09-16T16:59:59Z"));
		view().andExpect(jsonPath("$.renewalOpen").value(false));
		send(MINH).andExpect(status().isConflict());

		this.clock.setInstant(REMINDER);
		view().andExpect(jsonPath("$.renewalOpen").value(true));
		send(MINH).andExpect(status().isAccepted());
	}

	@Test
	void aDeadlineWhoseVerificationEndedIsObsolete() throws Exception {
		this.mvc.perform(delete("/api/v1/verification/student").with(student()).with(csrf()))
			.andExpect(status().isNoContent());

		this.clock.setInstant(EXPIRY);
		this.poller.runDue();

		assertThat(deadlines()).containsExactly("identity.verification_reminder OBSOLETE",
				"identity.verification_expiry OBSOLETE");
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE account_id = :id")
			.param("id", this.student).query(Long.class).single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE account_id = :id AND todo_key = 'identity.verify_student' AND closed_at IS NULL
				""").param("id", this.student).query(Long.class).single()).as("nobody is asked to verify again (Story 20.3)").isZero();
	}

	@Test
	void unlinkingDeletesAPendingRenewalCodeSoItsEntryFindsNothing() throws Exception {
		this.clock.setInstant(Instant.parse("2027-09-20T03:00:00Z"));
		send("minh.k23@vnu.edu.vn").andExpect(status().isAccepted());
		String code = deliveredCode();

		this.mvc.perform(delete("/api/v1/verification/student").with(student()).with(csrf()))
			.andExpect(status().isNoContent());

		assertThat(codes()).isZero();
		confirm(code).andExpect(status().isNotFound());
	}

	@Test
	void theExpiryDeletesAPendingRenewalCodeSoItsEntryFindsNothing() throws Exception {
		this.clock.setInstant(EXPIRY.minus(java.time.Duration.ofMinutes(5)));
		send("minh.k23@vnu.edu.vn").andExpect(status().isAccepted());
		String code = deliveredCode();

		this.clock.setInstant(EXPIRY);
		this.poller.runDue();

		assertThat(codes()).as("it held a copy of the locked name and major").isZero();
		confirm(code).andExpect(status().isNotFound());
	}

	@Test
	void aDeadlineFromBeforeTheVerificationTableIsObsolete() {
		this.transactions.executeWithoutResult(status -> this.timers.schedule(new DeadlineRequest(
				VerificationDeadlines.EXPIRY.key(), this.student, VERIFIED.toString(), null, EXPIRY.minusSeconds(60))));

		this.clock.setInstant(EXPIRY.minusSeconds(30));
		assertThat(this.poller.runDue()).as("the reminder and the old expiry").isEqualTo(2);

		assertThat(this.studentVerification.isVerified(this.student)).isTrue();
		assertThat(this.jdbc.sql("""
				SELECT status FROM timers.deadline WHERE subject_id = :id AND generation = :generation
				""").param("id", this.student).param("generation", VERIFIED.toString()).query(String.class).single())
			.isEqualTo("OBSOLETE");
	}

	@Test
	void theLastValidDayFollowsTheRenewalWindow() {
		assertThat(StudentVerifications.validUntil(LocalDate.of(2027, 9, 16))).isEqualTo(LocalDate.of(2027, 9, 30));
		assertThat(StudentVerifications.validUntil(LocalDate.of(2027, 9, 17))).isEqualTo(LocalDate.of(2028, 9, 30));
		assertThat(StudentVerifications.renewalOpens(LocalDate.of(2027, 9, 30))).isEqualTo(LocalDate.of(2027, 9, 17));
		assertThat(VerificationDeadlines.expiresAt(LocalDate.of(2027, 9, 30))).isEqualTo(EXPIRY);
	}

	/** Sends a code to {@code address} and enters it; the account is verified after this. */
	private ResultActions verify(String address) throws Exception {
		return verify(address, NAME, MAJOR);
	}

	private ResultActions verify(String address, String name, String major) throws Exception {
		send(address, name, major).andExpect(status().isAccepted());
		this.worker.runDue(Lane.EMAIL);
		RecordingMailSender.Sent sent = this.mail.sent().getLast();
		Matcher matcher = CODE.matcher(sent.subject());
		assertThat(matcher.matches()).isTrue();
		return this.mvc
			.perform(post("/api/v1/verification/school-email/confirm").with(student())
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + matcher.group(1) + "\"}"))
			.andExpect(status().isOk());
	}

	private ResultActions send(String address) throws Exception {
		return send(address, NAME, MAJOR);
	}

	private ResultActions send(String address, String name, String major) throws Exception {
		return this.mvc.perform(post("/api/v1/verification/school-email").with(student())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"noticeVersion\":\"1.0\",\"email\":\"" + address + "\",\"name\":\"" + name
					+ "\",\"major\":\"" + major + "\"}"));
	}

	/** Runs the email lane and returns the code of the last email. */
	private String deliveredCode() {
		this.worker.runDue(Lane.EMAIL);
		Matcher matcher = CODE.matcher(this.mail.sent().getLast().subject());
		assertThat(matcher.matches()).isTrue();
		return matcher.group(1);
	}

	private ResultActions confirm(String code) throws Exception {
		return this.mvc.perform(post("/api/v1/verification/school-email/confirm").with(student())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\"}"));
	}

	private long codes() {
		return this.jdbc.sql("SELECT count(*) FROM identity.school_email_code").query(Long.class).single();
	}

	private ResultActions view() throws Exception {
		return this.mvc.perform(get("/api/v1/verification").with(student()));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor student() {
		return authentication(IdentityTestSupport.authentication(this.student, Role.STUDENT));
	}

	/** The account's Deadlines as "kind STATUS", with " due" for a pending one, oldest first. */
	private List<String> deadlines() {
		return this.jdbc.sql("""
				SELECT kind, status, due_at FROM timers.deadline WHERE subject_id = :id
				ORDER BY created_at, CASE kind WHEN 'identity.verification_reminder' THEN 0 ELSE 1 END
				""")
			.param("id", this.student)
			.query((rs, n) -> rs.getString("kind") + " " + rs.getString("status")
					+ (rs.getTimestamp("due_at") == null ? "" : " " + rs.getTimestamp("due_at").toInstant()))
			.list();
	}

}
