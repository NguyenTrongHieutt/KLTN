package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import vn.vibefix.identity.StudentVerification;
import vn.vibefix.notification.internal.RecordingMailSender;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/** FR-5, AD-7, UX-DR16, UX-DR27: the reminder 14 days before 30 September, the expiry, and renewal. */
class VerificationExpiryIT extends IntegrationTest {

	private static final Pattern CODE = Pattern.compile("Mã xác minh vibefix của bạn: ([0-9]{6}) .*");

	private static final String MINH = "minh@sis.hust.edu.vn";

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

	private UUID student;

	@BeforeEach
	void aVerifiedStudent() throws Exception {
		this.student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
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
	void atTheExpiryTheStudentIsUnverifiedToldAndAskedToVerifyAgain() throws Exception {
		this.clock.setInstant(EXPIRY);
		assertThat(this.poller.runDue()).as("the reminder, then the expiry").isEqualTo(2);

		assertThat(this.studentVerification.isVerified(this.student)).isFalse();
		assertThat(this.jdbc.sql("""
				SELECT type FROM notification.notification WHERE account_id = :id ORDER BY created_at, type
				""").param("id", this.student).query(String.class).list())
			.contains("identity.verification_expired", "identity.verification_expiring");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo
				WHERE account_id = :id AND todo_key = 'identity.verify_student' AND closed_at IS NULL
				""").param("id", this.student).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT actor_type FROM audit.audit_entry WHERE action = 'identity.verification_expired'
				""").query(String.class).single()).isEqualTo("SYSTEM");
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).extracting(RecordingMailSender.Sent::subject)
			.contains("Xác minh sinh viên đã hết hạn");
		view().andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"));
		assertThat(deadlines()).containsExactly("identity.verification_reminder FIRED", "identity.verification_expiry FIRED");
	}

	@Test
	void renewingInTheLastFourteenDaysCountsForTheNextSchoolYearAndObsoletesTheOldDeadlines() throws Exception {
		this.clock.setInstant(Instant.parse("2027-09-20T03:00:00Z"));
		view().andExpect(jsonPath("$.status").value("VERIFIED"))
			.andExpect(jsonPath("$.renewalOpen").value(true))
			.andExpect(jsonPath("$.pendingCode").doesNotExist());

		verify("minh.k23@vnu.edu.vn").andExpect(jsonPath("$.verifiedUntil").value("2028-09-30"))
			.andExpect(jsonPath("$.schoolEmail").value("minh.k23@vnu.edu.vn"))
			.andExpect(jsonPath("$.renewalOpen").value(false));

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
	void aDeadlineWhoseVerificationIsGoneIsObsolete() {
		this.jdbc.sql("DELETE FROM identity.student_verification WHERE account_id = :id").param("id", this.student).update();

		this.clock.setInstant(EXPIRY);
		this.poller.runDue();

		assertThat(deadlines()).containsExactly("identity.verification_reminder OBSOLETE",
				"identity.verification_expiry OBSOLETE");
		assertThat(this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE account_id = :id")
			.param("id", this.student).query(Long.class).single()).isZero();
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
		send(address).andExpect(status().isAccepted());
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
		return this.mvc.perform(post("/api/v1/verification/school-email").with(student())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + address + "\"}"));
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
