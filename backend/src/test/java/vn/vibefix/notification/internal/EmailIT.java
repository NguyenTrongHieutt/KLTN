package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.notification.EmailClass;
import vn.vibefix.notification.EmailRequest;
import vn.vibefix.notification.Emails;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;

/** AD-6, AD-27, AD-34: the email lane, its priority classes, and the daily budget. */
class EmailIT extends IntegrationTest {

	private static final UUID LAN = UUID.fromString("0190f0c3-0000-7000-8000-00000000b001");

	@Autowired
	private Emails emails;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private java.util.List<PersonalDataParticipant> participants;

	@Test
	void anEmailGoesOutOnceFromItsTemplateAndItsAddressAndValuesArePurged() {
		UUID cause = UUID.randomUUID();
		send(cause, EmailClass.ONE_TIME_CODE, "test.greeting", Map.of("name", "Lan", "code", "482915"));
		send(cause, EmailClass.ONE_TIME_CODE, "test.greeting", Map.of("name", "Lan", "code", "482915"));
		assertThat(this.mail.sent()).as("nothing before the poller runs").isEmpty();

		this.worker.runDue(Lane.EMAIL);
		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).singleElement().satisfies(sent -> {
			assertThat(sent.to()).isEqualTo("lan@truong.edu.vn");
			assertThat(sent.subject()).isEqualTo("Chào Lan, mã vibefix của bạn là 482915");
			assertThat(sent.text()).contains("Mã xác minh vibefix của bạn là 482915")
				.contains("Mở vibefix: http://localhost:5173/viec-can-lam");
			assertThat(sent.html()).contains("<p>Xin chào Lan,</p>").contains("href=\"http://localhost:5173/viec-can-lam\"");
		});
		assertThat(this.jdbc.sql("""
				SELECT status || ' ' || coalesce(personal_data::text, 'purged') || ' ' || payload::text
				FROM operations.external_operation
				""").query(String.class).single()).startsWith("SUCCEEDED purged ")
			.doesNotContain("lan@truong.edu.vn")
			.doesNotContain("482915");
	}

	@Test
	void competingEmailsGoTransactionalFirstThenCodesThenReminders() {
		send(UUID.randomUUID(), EmailClass.REMINDER, "test.reminder", Map.of());
		send(UUID.randomUUID(), EmailClass.ONE_TIME_CODE, "test.greeting", Map.of("name", "Mã", "code", "1"));
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.greeting", Map.of("name", "Việc", "code", "2"));

		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).extracting(RecordingMailSender.Sent::subject)
			.containsExactly("Chào Việc, mã vibefix của bạn là 2", "Chào Mã, mã vibefix của bạn là 1",
					"Nhắc việc trên vibefix");
	}

	@Test
	void pastTheDailyBudgetEmailWaitsForTheNextUtcDayWithoutUsingAnAttempt() {
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC);
		setSentToday(100);
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());

		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).isEmpty();
		Instant midnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
		assertThat(this.jdbc.sql("SELECT status || ' ' || attempt_count FROM operations.external_operation")
			.query(String.class)
			.single()).isEqualTo("PENDING 0");
		assertThat(this.jdbc.sql("SELECT next_attempt_at FROM operations.external_operation")
			.query(Timestamp.class)
			.single()
			.toInstant()).isEqualTo(midnight);

		this.clock.setInstant(midnight);
		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).hasSize(1);
		assertThat(sentOn(today.plusDays(1))).isOne();
	}

	@Test
	void theSendThatCrossesEightyPercentMarksTheDayOnce() {
		setSentToday(78);
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());
		this.worker.runDue(Lane.EMAIL);
		assertThat(alertedAt()).as("79 of 100").isNull();

		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());
		this.worker.runDue(Lane.EMAIL);
		Instant marked = alertedAt();
		assertThat(marked).as("80 of 100").isEqualTo(this.clock.instant());

		this.clock.advance(Duration.ofMinutes(5));
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());
		this.worker.runDue(Lane.EMAIL);
		assertThat(alertedAt()).isEqualTo(marked);
		// The mark is published once, for admin's alert to every Administrator.
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE event_type LIKE '%EmailBudgetAlert'")
			.query(Long.class).single()).isOne();
	}

	@Test
	void anEmailToAnErasedAccountIsObsoleteAndNotSent() {
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());
		this.participants.forEach(participant -> participant.eraseAccount(LAN));

		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).isEmpty();
		assertThat(statusAndCode()).isEqualTo("OBSOLETE RECIPIENT_ERASED");
	}

	/** AD-42: a one-time code's email still waiting once its code expired is aborted, its address purged. */
	@Test
	void aOneTimeCodesEmailStillWaitingOnceItsCodeExpiredIsAbortedWithItsAddressPurged() {
		setSentToday(100);
		send(UUID.randomUUID(), EmailClass.ONE_TIME_CODE, "test.greeting", Map.of("name", "Lan", "code", "482915"));
		this.worker.runDue(Lane.EMAIL);
		assertThat(statusAndCode()).as("the budget is used up").startsWith("PENDING");

		this.clock.setInstant(LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC).plusDays(1)
			.atStartOfDay(ZoneOffset.UTC)
			.toInstant());
		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).isEmpty();
		assertThat(statusAndCode()).isEqualTo("ABORTED CODE_EXPIRED");
		assertThat(this.jdbc.sql("SELECT personal_data IS NULL FROM operations.external_operation")
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE event_type LIKE '%OperationFailed'")
			.query(Long.class)
			.single()).as("no Administrator alert").isZero();
	}

	@Test
	void aOneTimeCodesEmailGoesOutWithinItsTenMinutes() {
		send(UUID.randomUUID(), EmailClass.ONE_TIME_CODE, "test.greeting", Map.of("name", "Lan", "code", "482915"));
		this.clock.advance(EmailClass.ONE_TIME_CODE_LIFETIME.minusSeconds(1));
		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).hasSize(1);
		assertThat(statusAndCode()).isEqualTo("SUCCEEDED");
	}

	@Test
	void aRefusedRecipientAbortsAndGivesTheBudgetBack() {
		this.mail.rejectNext();
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());

		this.worker.runDue(Lane.EMAIL);
		this.clock.advance(Duration.ofHours(1));
		this.worker.runDue(Lane.EMAIL);

		assertThat(statusAndCode()).isEqualTo("ABORTED EMAIL_REJECTED");
		assertThat(sentOn(LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC))).isZero();
	}

	@Test
	void anUnavailableServerIsRetriedAfter30Seconds() {
		this.mail.failNext();
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());

		this.worker.runDue(Lane.EMAIL);
		assertThat(statusAndCode()).isEqualTo("PENDING SMTP_UNAVAILABLE");
		this.clock.advance(Duration.ofSeconds(30));
		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).hasSize(1);
		assertThat(sentOn(LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC))).isOne();
	}

	@Test
	void anAnswerLostAfterSendingSendsAgain() {
		this.mail.loseAnswerNext();
		send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.reminder", Map.of());

		this.worker.runDue(Lane.EMAIL);
		this.clock.advance(Duration.ofSeconds(30));
		this.worker.runDue(Lane.EMAIL);

		assertThat(this.mail.sent()).as("at least once").hasSize(2);
		assertThat(statusAndCode()).isEqualTo("SUCCEEDED");
	}

	@Test
	void aBadRequestFailsTheCallerAndRecordsNothing() {
		assertThatThrownBy(() -> send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.greeting", Map.of("name", "Lan")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("{code}");
		assertThatThrownBy(() -> send(UUID.randomUUID(), EmailClass.TRANSACTIONAL, "test.nothing", Map.of()))
			.isInstanceOf(IllegalArgumentException.class);
		for (String address : new String[] { "not an address", "Lan <lan@truong.edu.vn>", "a@b.vn, c@d.vn",
				"lan@truong.edu.vn\r\nBcc: x@y.vn" }) {
			assertThatThrownBy(() -> inTransaction(() -> this.emails.send(new EmailRequest("test.reminder",
					UUID.randomUUID(), LAN, address, EmailClass.TRANSACTIONAL, Map.of()))))
				.as(address)
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> this.emails.send(new EmailRequest("test.reminder", UUID.randomUUID(), LAN,
				"lan@truong.edu.vn", EmailClass.TRANSACTIONAL, Map.of())))
			.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation").query(Long.class).single())
			.isZero();
	}

	private void send(UUID cause, EmailClass emailClass, String type, Map<String, String> parameters) {
		inTransaction(() -> this.emails
			.send(new EmailRequest(type, cause, LAN, "lan@truong.edu.vn", emailClass, parameters)));
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> work.run());
	}

	private void setSentToday(int sent) {
		this.jdbc.sql("INSERT INTO notification.email_budget_day (day, sent) VALUES (:day, :sent)")
			.param("day", Date.valueOf(LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC)))
			.param("sent", sent)
			.update();
	}

	private int sentOn(LocalDate day) {
		return this.jdbc.sql("SELECT coalesce(max(sent), 0) FROM notification.email_budget_day WHERE day = :day")
			.param("day", Date.valueOf(day))
			.query(Integer.class)
			.single();
	}

	private Instant alertedAt() {
		Timestamp alerted = this.jdbc.sql("SELECT alerted_at FROM notification.email_budget_day WHERE day = :day")
			.param("day", Date.valueOf(LocalDate.ofInstant(this.clock.instant(), ZoneOffset.UTC)))
			.query((row, number) -> row.getTimestamp("alerted_at"))
			.list()
			.getFirst();
		return alerted == null ? null : alerted.toInstant();
	}

	private String statusAndCode() {
		return this.jdbc.sql("SELECT trim(status || ' ' || coalesce(error_code, '')) FROM operations.external_operation")
			.query(String.class)
			.single();
	}

}
