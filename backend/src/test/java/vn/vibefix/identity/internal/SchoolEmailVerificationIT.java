package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.Notices;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.identity.internal.StudentVerifications.Confirmation;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.notification.internal.RecordingMailSender;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PostgresTestDatabase;

/**
 * FR-5, FR-80, AD-34, AD-42, UX-DR93: the school email code for any person's account after N-26, its
 * limits, the verification it writes, and "Bỏ xác minh". The address is kept by no table and returned by
 * no answer: the code row and the verification are keyed by its HMAC, and only the email operation's
 * personal data holds it until the email completes.
 */
class SchoolEmailVerificationIT extends IntegrationTest {

	private static final Pattern CODE = Pattern.compile("Mã xác minh vibefix của bạn: ([0-9]{6}) \\(hết hạn sau 10 phút\\)");

	private static final String MINH = "minh@sis.hust.edu.vn";

	private static final String NAME = "Trần Quang Minh";

	private static final String MAJOR = "Khoa học máy tính";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private Todos todos;

	@Autowired
	private StudentVerification studentVerification;

	@Autowired
	private StudentVerifications verifications;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private VerificationCodePurge purge;

	@Autowired
	private PersonSignalKeys keys;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	/** A Google account, which holds no role (Story 19.1): verifying needs none. */
	private UUID minh;

	/** The dev seed, which integration tests load, allowlists {@code hust.edu.vn}. */
	@BeforeEach
	void aPerson() {
		this.minh = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-minh", "3.0");
	}

	@Test
	void aSendRecordsN26AndKeysTheCodeByTheAddressHmacWithTheAddressOnlyInTheEmailOperation() throws Exception {
		send(this.minh, MINH).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.pendingCode.school").value("hust.edu.vn"))
			.andExpect(jsonPath("$.pendingCode.attemptsLeft").value(5))
			.andExpect(jsonPath("$.pendingCode.expired").value(false))
			.andExpect(jsonPath("$.pendingCode.email").doesNotExist());

		assertThat(this.jdbc.sql("""
				SELECT notice || ' ' || notice_version FROM identity.acknowledgment WHERE account_id = :id AND notice <> 'PRIVACY'
				""").param("id", this.minh).query(String.class).list()).containsExactly("VERIFY_STUDENT 1.0");
		assertThat(this.jdbc.sql("""
				SELECT address_hmac || ' ' || key_version || ' ' || school || ' ' || declared_name || ' ' || declared_major
				FROM identity.school_email_code WHERE account_id = :id
				""").param("id", this.minh).query(String.class).single())
			.isEqualTo(this.keys.current("EMAIL", MINH) + " 1 hust.edu.vn " + NAME + " " + MAJOR);
		assertThat(this.jdbc.sql("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'identity' AND table_name = 'school_email_code' AND column_name LIKE '%email%'
				""").query(String.class).list()).as("no address column").isEmpty();
		// The address is the email operation's personal data, never its payload.
		assertThat(this.jdbc.sql("""
				SELECT (personal_data ->> 'to') || ' ' || (payload::text LIKE '%' || :address || '%')
				FROM operations.external_operation WHERE type = 'notification.email'
				""").param("address", MINH).query(String.class).single()).isEqualTo(MINH + " false");

		String code = deliveredCode(MINH);
		RecordingMailSender.Sent sent = this.mail.sent().getLast();
		assertThat(sent.text()).contains(code).contains("hết hạn sau 10 phút").contains("trang Xác minh");
		assertThat(this.jdbc.sql("SELECT * FROM identity.school_email_code").query().listOfRows())
			.singleElement()
			.satisfies(row -> assertThat(row.values().toString()).doesNotContain(code));
		assertThat(this.jdbc.sql("SELECT personal_data IS NULL FROM operations.external_operation WHERE type = 'notification.email'")
			.query(Boolean.class)
			.single()).as("purged once the email completed").isTrue();
	}

	@Test
	void anOldOrMissingNoticeVersionSendsNothing() throws Exception {
		send(this.minh, MINH, "0.9", NAME, MAJOR).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		this.mvc.perform(post("/api/v1/verification/school-email").with(as(this.minh))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + MINH + "\",\"name\":\"" + NAME + "\",\"major\":\"" + MAJOR + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("noticeVersion"));

		nothingSent();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment WHERE notice = 'VERIFY_STUDENT'")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void theDomainIsComparedWithoutLetterCaseAndASubdomainIsAllowed() throws Exception {
		send(this.minh, "Minh@SIS.Hust.EDU.vn").andExpect(status().isAccepted())
			.andExpect(jsonPath("$.pendingCode.school").value("hust.edu.vn"));
		assertThat(this.jdbc.sql("SELECT address_hmac FROM identity.school_email_code").query(String.class).single())
			.as("the normalized address's HMAC").isEqualTo(this.keys.current("EMAIL", MINH));
	}

	@Test
	void anAddressOutsideTheAllowlistIsRefusedAndNothingIsSent() throws Exception {
		for (String address : new String[] { "khoa@gmail.com", "khoa@evilhust.edu.vn", "khoa@hust.edu.vn.evil.com" }) {
			send(this.minh, address).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
				.andExpect(jsonPath("$.reason").value("EMAIL_DOMAIN_NOT_ALLOWED"));
		}
		nothingSent();
	}

	@Test
	void aFirstVerificationDeclaresTheNameAndTheMajor() throws Exception {
		send(this.minh, MINH, Notices.VERIFY_STUDENT_VERSION, "  ", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.errors[1].field").value("major"));
		nothingSent();
	}

	@Test
	void anAddressThatVerifiesAnotherLiveAccountIsRefused() throws Exception {
		UUID other = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-other", "3.0");
		send(other, MINH).andExpect(status().isAccepted());
		confirm(other, deliveredCode(MINH)).andExpect(status().isOk());
		this.mail.reset();

		send(this.minh, "MINH@sis.hust.edu.vn").andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("STUDENT_EMAIL_IN_USE"));
		nothingSent();

		// Once the other account lets it go, the address verifies this one.
		this.mvc.perform(delete("/api/v1/verification/student").with(as(other)).with(csrf()))
			.andExpect(status().isNoContent());
		send(this.minh, MINH).andExpect(status().isAccepted());
	}

	@Test
	void anAddressTakenBetweenTheSendAndTheEntryIsRefusedAndNothingIsWritten() throws Exception {
		UUID other = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-other", "3.0");
		send(this.minh, MINH).andExpect(status().isAccepted());
		String mine = deliveredCode(MINH);
		send(other, MINH).andExpect(status().isAccepted());
		confirm(other, deliveredCode(MINH)).andExpect(status().isOk());

		confirm(this.minh, mine).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("STUDENT_EMAIL_IN_USE"));

		assertThat(this.studentVerification.isVerified(this.minh)).isFalse();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.verification WHERE account_id = :id")
			.param("id", this.minh).query(Long.class).single()).isZero();
	}

	@Test
	void anAccountGetsThreeSendsPerClockHour() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T03:10:00Z"));
		for (int i = 0; i < 3; i++) {
			send(this.minh, MINH).andExpect(status().isAccepted());
			this.clock.advance(Duration.ofMinutes(5));
		}

		send(this.minh, MINH).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().string("Retry-After",
					Long.toString(Duration.between(this.clock.instant(), Instant.parse("2026-10-19T04:00:00Z")).toSeconds())));
		assertThat(this.jdbc.sql("SELECT sends FROM identity.school_email_send_hour WHERE account_id = :id")
			.param("id", this.minh).query(Integer.class).single()).as("the refused send is not counted").isEqualTo(3);

		this.clock.setInstant(Instant.parse("2026-10-19T04:00:00Z"));
		send(this.minh, MINH).andExpect(status().isAccepted());
	}

	@Test
	void anAddressGetsTenSendsPerUtcDay() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T01:00:00Z"));
		for (int i = 0; i < 10; i++) {
			if (i > 0 && i % 3 == 0) {
				this.clock.advance(Duration.ofMinutes(61));
			}
			send(this.minh, MINH).andExpect(status().isAccepted());
		}
		this.clock.advance(Duration.ofMinutes(61));

		send(this.minh, MINH).andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", Long.toString(
					Duration.between(this.clock.instant(), Instant.parse("2026-10-20T00:00:00Z")).toSeconds())));
		assertThat(this.jdbc.sql("SELECT address_hmac || ' ' || sends FROM identity.school_email_send_day")
			.query(String.class)
			.single()).as("counted by the HMAC; the refused send is not counted")
			.isEqualTo(this.keys.current("EMAIL", MINH) + " 10");

		this.clock.setInstant(Instant.parse("2026-10-20T00:00:01Z"));
		send(this.minh, MINH).andExpect(status().isAccepted());
	}

	@Test
	void aWrongCodeCountsAndTheViewSaysHowManyAttemptsAreLeft() throws Exception {
		send(this.minh, MINH);
		String wrong = otherThan(deliveredCode(MINH));

		for (int i = 0; i < 2; i++) {
			confirm(this.minh, wrong).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
				.andExpect(jsonPath("$.reason").value("WRONG_CODE"));
		}

		view(this.minh).andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.pendingCode.attemptsLeft").value(3));
	}

	@Test
	void aCodeExpiresAfterTenMinutesAndThePurgeDeletesIt() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T03:55:00Z"));
		send(this.minh, MINH);
		String code = deliveredCode(MINH);
		this.clock.advance(Duration.ofMinutes(10).plusSeconds(1));

		confirm(this.minh, code).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CODE_EXPIRED"));
		view(this.minh).andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.pendingCode.expired").value(true));

		assertThat(this.purge.purge()).as("the expired code and the past hour's counter").isEqualTo(2);
		view(this.minh).andExpect(jsonPath("$.pendingCode").doesNotExist());
		confirm(this.minh, code).andExpect(status().isNotFound());
	}

	@Test
	void theSixthAttemptIsRefusedEvenWithTheRightCode() throws Exception {
		send(this.minh, MINH);
		String code = deliveredCode(MINH);
		for (int i = 0; i < 5; i++) {
			confirm(this.minh, otherThan(code)).andExpect(jsonPath("$.reason").value("WRONG_CODE"));
		}
		view(this.minh).andExpect(jsonPath("$.pendingCode.attemptsLeft").value(0));

		confirm(this.minh, code).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("TOO_MANY_ATTEMPTS"));
		assertThat(this.studentVerification.isVerified(this.minh)).isFalse();
	}

	@Test
	void concurrentEntriesNeverPassFiveAttempts() throws Exception {
		send(this.minh, MINH);
		String wrong = otherThan(deliveredCode(MINH));
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Future<Confirmation>> results = new ArrayList<>();
		try {
			for (int i = 0; i < 8; i++) {
				Callable<Confirmation> entry = () -> this.verifications.confirm(this.minh, wrong);
				results.add(pool.submit(entry));
			}
			List<String> reasons = new ArrayList<>();
			for (Future<Confirmation> result : results) {
				reasons.add(((Confirmation.Refused) result.get()).refusal().reason().orElseThrow());
			}
			assertThat(reasons).filteredOn("WRONG_CODE"::equals).hasSize(5);
			assertThat(reasons).filteredOn("TOO_MANY_ATTEMPTS"::equals).hasSize(3);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.sql("SELECT attempts FROM identity.school_email_code").query(Integer.class).single())
			.isEqualTo(5);
	}

	@Test
	void aNewSendReplacesThePendingCode() throws Exception {
		send(this.minh, MINH);
		String first = deliveredCode(MINH);
		this.clock.advance(Duration.ofMinutes(1));
		send(this.minh, MINH);
		String second = deliveredCode(MINH);

		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.school_email_code").query(Long.class).single())
			.as("the first code is deleted").isOne();
		if (!first.equals(second)) {
			confirm(this.minh, first).andExpect(jsonPath("$.reason").value("WRONG_CODE"));
		}
		confirm(this.minh, second).andExpect(status().isOk());
	}

	@Test
	void theRightCodeWritesTheVerificationProjectsItsFactsRecordsTheSignalAuditsAndClosesTheToDo() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T03:00:00Z"));
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> this.todos
			.open(new TodoRequest(SignIns.VERIFY_STUDENT, this.minh, Audience.SUBJECT, this.minh, Map.of(),
					SignIns.VERIFICATION_PATH, null, null)));
		send(this.minh, MINH);

		confirm(this.minh, deliveredCode(MINH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VERIFIED"))
			.andExpect(jsonPath("$.method").value("SCHOOL_EMAIL"))
			.andExpect(jsonPath("$.school").value("hust.edu.vn"))
			.andExpect(jsonPath("$.name").value(NAME))
			.andExpect(jsonPath("$.major").value(MAJOR))
			.andExpect(jsonPath("$.verifiedAt").value("2026-10-19T03:00:00Z"))
			.andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"))
			.andExpect(jsonPath("$.renewalOpen").value(false))
			.andExpect(jsonPath("$.pendingCode").doesNotExist());

		assertThat(this.studentVerification.isVerified(this.minh)).isTrue();
		assertThat(this.jdbc.sql("""
				SELECT kind || ' ' || provider || ' ' || subject_hmac || ' ' || key_version || ' ' || consent_version || ' '
				       || valid_until || ' ' || (facts ->> 'school') || ' ' || (facts ->> 'name') || ' ' || (facts ->> 'major')
				       || ' ' || (facts ->> 'verifiedMonth') || ' ' || (ended_at IS NULL)
				FROM identity.verification WHERE account_id = :id
				""").param("id", this.minh).query(String.class).single())
			.isEqualTo("STUDENT EMAIL_CODE " + this.keys.current("EMAIL", MINH) + " 1 1.0 2027-09-30 hust.edu.vn " + NAME + " "
					+ MAJOR + " 2026-10 true");
		assertThat(this.jdbc.sql("""
				SELECT student_name || ' ' || student_school || ' ' || student_major FROM identity.profile WHERE account_id = :id
				""").param("id", this.minh).query(String.class).single())
			.as("the profile's projection").isEqualTo(NAME + " hust.edu.vn " + MAJOR);
		assertThat(this.jdbc.sql("""
				SELECT source || ' ' || hmac FROM identity.person_signal WHERE account_id = :id AND source = 'SCHOOL_EMAIL'
				""").param("id", this.minh).query(String.class).single())
			.isEqualTo("SCHOOL_EMAIL " + this.keys.current("EMAIL", MINH));
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.school_email_code").query(Long.class).single())
			.as("a used code is deleted").isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.student_verified' AND actor_account_id = :id
				""").param("id", this.minh).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT closed_at FROM notification.todo WHERE todo_key = 'identity.verify_student' AND account_id = :id
				""").param("id", this.minh).query(Timestamp.class).single()).isNotNull();
		// ACC-1 reads the declared name and major from its verified zone.
		this.mvc.perform(get("/api/v1/profile").with(as(this.minh)))
			.andExpect(jsonPath("$.verified.student.school").value("hust.edu.vn"))
			.andExpect(jsonPath("$.verified.student.name").value(NAME))
			.andExpect(jsonPath("$.verified.student.major").value(MAJOR))
			.andExpect(jsonPath("$.verified.student.validUntil").value("2027-09-30"))
			.andExpect(jsonPath("$.studentDetails.major").value(MAJOR));
		send(this.minh, MINH).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").doesNotExist());
		confirm(this.minh, "123456").andExpect(status().isConflict());
	}

	@Test
	void theAddressIsInNoTableAnswerExportNotificationOrAuditEntry() throws Exception {
		send(this.minh, MINH).andExpect(status().isAccepted());
		String answers = text(get("/api/v1/verification").with(as(this.minh)));
		answers += text(confirmRequest(this.minh, deliveredCode(MINH)));
		answers += text(get("/api/v1/verification").with(as(this.minh)));
		answers += text(get("/api/v1/profile").with(as(this.minh)));
		answers += text(get("/api/v1/notifications").with(as(this.minh)));
		String export = text(get("/api/v1/account/data/export").with(as(this.minh)));
		this.worker.runDue(Lane.EMAIL);

		assertThat(answers).contains("hust.edu.vn").doesNotContainIgnoringCase(MINH).doesNotContainIgnoringCase("minh@");
		assertThat(export).contains("hust.edu.vn").contains(NAME).contains("VERIFY_STUDENT")
			.doesNotContainIgnoringCase(MINH)
			.doesNotContain(this.keys.current("EMAIL", MINH));
		assertThat(tablesHolding(MINH)).as("every table: codes, counters, verifications, notifications, audit, operations")
			.isEmpty();
	}

	@Test
	void unlinkingEndsTheRowUnlinkedDeletingItsFactsAndKeepingTheHmac() throws Exception {
		send(this.minh, MINH);
		confirm(this.minh, deliveredCode(MINH)).andExpect(status().isOk());

		this.mvc.perform(delete("/api/v1/verification/student").with(as(this.minh)).with(csrf()))
			.andExpect(status().isNoContent());

		assertThat(this.studentVerification.isVerified(this.minh)).isFalse();
		assertThat(this.jdbc.sql("""
				SELECT ended_reason || ' ' || (facts IS NULL) || ' ' || subject_hmac FROM identity.verification
				WHERE account_id = :id
				""").param("id", this.minh).query(String.class).single())
			.isEqualTo("UNLINKED true " + this.keys.current("EMAIL", MINH));
		assertThat(this.jdbc.sql("""
				SELECT coalesce(student_name, '-') || coalesce(student_school, '-') || coalesce(student_major, '-')
				FROM identity.profile WHERE account_id = :id
				""").param("id", this.minh).query(String.class).single()).isEqualTo("---");
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id AND source = 'SCHOOL_EMAIL'")
			.param("id", this.minh).query(Long.class).single()).as("the signal stays").isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.student_verification_unlinked'
				""").query(Long.class).single()).isOne();
		view(this.minh).andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.school").doesNotExist())
			.andExpect(jsonPath("$.name").doesNotExist());
		this.mvc.perform(get("/api/v1/profile").with(as(this.minh)))
			.andExpect(jsonPath("$.verified.student").doesNotExist())
			.andExpect(jsonPath("$.studentDetails").doesNotExist());

		this.mvc.perform(delete("/api/v1/verification/student").with(as(this.minh)).with(csrf()))
			.andExpect(status().isNotFound());
		// Verifying again declares the name and the major again: the old ones went with the facts.
		send(this.minh, MINH, Notices.VERIFY_STUDENT_VERSION, "Trần Minh", "Toán tin").andExpect(status().isAccepted());
		confirm(this.minh, deliveredCode(MINH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Trần Minh"))
			.andExpect(jsonPath("$.major").value("Toán tin"));
	}

	@Test
	void theVerificationLapsesAfterItsThirtiethOfSeptemberInVietnam() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T03:00:00Z"));
		send(this.minh, MINH);
		confirm(this.minh, deliveredCode(MINH)).andExpect(status().isOk());

		this.clock.setInstant(Instant.parse("2027-09-30T16:59:59Z"));
		assertThat(this.studentVerification.isVerified(this.minh)).as("23:59:59 on 30/09 in Vietnam").isTrue();
		this.clock.setInstant(Instant.parse("2027-09-30T17:00:00Z"));
		assertThat(this.studentVerification.isVerified(this.minh)).as("00:00 on 01/10 in Vietnam").isFalse();
		// "Chưa xác minh" at once, before the expiry Deadline ends the row: no fact is shown.
		view(this.minh).andExpect(jsonPath("$.status").value("EXPIRED"))
			.andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"))
			.andExpect(jsonPath("$.school").doesNotExist())
			.andExpect(jsonPath("$.name").doesNotExist());
		this.mvc.perform(get("/api/v1/profile").with(as(this.minh))).andExpect(jsonPath("$.verified.student").doesNotExist());
		// A lapsed account verifies again, declaring its details again; the lapsed row ends as the new one comes.
		send(this.minh, MINH).andExpect(status().isAccepted());
		confirm(this.minh, deliveredCode(MINH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.verifiedUntil").value("2028-09-30"));
		assertThat(this.jdbc.sql("SELECT coalesce(ended_reason, 'LIVE') FROM identity.verification ORDER BY verified_at")
			.query(String.class)
			.list()).containsExactly("EXPIRED", "LIVE");
	}

	@Test
	void thePurgeDeletesExpiredCodesAndPastCounters() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T23:50:00Z"));
		send(this.minh, MINH);
		this.clock.setInstant(Instant.parse("2026-10-20T00:05:00Z"));
		send(this.minh, MINH);

		this.clock.setInstant(Instant.parse("2026-10-20T00:10:00Z"));
		assertThat(this.purge.purge()).as("the 19/10 day counter and its 23:00 hour counter").isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT day FROM identity.school_email_send_day").query(LocalDate.class).single())
			.isEqualTo(LocalDate.of(2026, 10, 20));
		assertThat(this.jdbc.sql("SELECT hour FROM identity.school_email_send_hour").query(Timestamp.class).single())
			.isEqualTo(Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")));
		view(this.minh).andExpect(jsonPath("$.pendingCode.school").value("hust.edu.vn"));

		this.clock.setInstant(Instant.parse("2026-10-20T00:15:00Z"));
		assertThat(this.purge.purge()).as("the code, past its 10 minutes").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.school_email_code").query(Long.class).single()).isZero();
	}

	/** Story 1.10 counts the last 14 days (from 17 September) for the next school year, as renewal does. */
	@Test
	void theLastValidDayIsTheNextThirtiethOfSeptember() {
		assertThat(StudentVerifications.validUntil(LocalDate.of(2026, 9, 16))).isEqualTo(LocalDate.of(2026, 9, 30));
		assertThat(StudentVerifications.validUntil(LocalDate.of(2026, 9, 29))).isEqualTo(LocalDate.of(2027, 9, 30));
		assertThat(StudentVerifications.validUntil(LocalDate.of(2026, 9, 30))).isEqualTo(LocalDate.of(2027, 9, 30));
		assertThat(StudentVerifications.validUntil(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2027, 9, 30));
		assertThat(StudentVerifications.validUntil(LocalDate.of(2027, 1, 5))).isEqualTo(LocalDate.of(2027, 9, 30));
	}

	@Test
	void malformedInputIsAValidationErrorAndAnEntryWithoutACodeFindsNothing() throws Exception {
		send(this.minh, "not-an-email").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("email"));
		confirm(this.minh, "12ab56").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("code"));
		confirm(this.minh, "123456").andExpect(status().isNotFound());
		this.mvc.perform(delete("/api/v1/verification/student").with(as(this.minh)).with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	void anyPersonVerifiesButAnAdministratorHasNoVerification() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		RequestPostProcessor customer = authentication(IdentityTestSupport.authentication(lan, Role.CUSTOMER));
		this.mvc.perform(get("/api/v1/verification").with(customer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UNVERIFIED"));
		this.mvc.perform(post("/api/v1/verification/school-email").with(customer)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("lan@hcmut.edu.vn", Notices.VERIFY_STUDENT_VERSION, NAME, MAJOR)))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.pendingCode.school").value("hcmut.edu.vn"));

		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.mvc.perform(get("/api/v1/verification")
			.with(IdentityTestSupport.signedIn(this.sessions, admin, Role.ADMINISTRATOR, this.clock.instant())))
			.andExpect(status().isNotFound());
	}

	private ResultActions send(UUID account, String email) throws Exception {
		return send(account, email, Notices.VERIFY_STUDENT_VERSION, NAME, MAJOR);
	}

	private ResultActions send(UUID account, String email, String noticeVersion, @Nullable String name,
			@Nullable String major) throws Exception {
		return this.mvc.perform(post("/api/v1/verification/school-email").with(as(account))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(email, noticeVersion, name, major)));
	}

	private static String body(String email, String noticeVersion, @Nullable String name, @Nullable String major) {
		return "{\"noticeVersion\":\"" + noticeVersion + "\",\"email\":\"" + email + "\""
				+ (name == null ? "" : ",\"name\":\"" + name + "\"") + (major == null ? "" : ",\"major\":\"" + major + "\"")
				+ "}";
	}

	private ResultActions confirm(UUID account, String code) throws Exception {
		return this.mvc.perform(confirmRequest(account, code));
	}

	private RequestBuilder confirmRequest(UUID account, String code) {
		return post("/api/v1/verification/school-email/confirm").with(as(account))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\"}");
	}

	private ResultActions view(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/verification").with(as(account)));
	}

	private String text(RequestBuilder request) throws Exception {
		return this.mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private void nothingSent() {
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.school_email_code").query(Long.class).single()).isZero();
	}

	/** Runs the email lane and returns the code of the last email to {@code address}. */
	private String deliveredCode(String address) {
		this.worker.runDue(Lane.EMAIL);
		RecordingMailSender.Sent sent = this.mail.sent()
			.stream()
			.filter(mail -> mail.to().equals(address))
			.reduce((first, second) -> second)
			.orElseThrow();
		Matcher matcher = CODE.matcher(sent.subject());
		assertThat(matcher.matches()).as("subject %s", sent.subject()).isTrue();
		return matcher.group(1);
	}

	/** Every table, in every schema, with a row whose text holds {@code value}, ignoring letter case. */
	private static List<String> tablesHolding(String value) throws Exception {
		List<String> tables = new ArrayList<>();
		List<String> holding = new ArrayList<>();
		try (Connection connection = database.superuserConnection(PostgresTestDatabase.DATABASE)) {
			try (Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery("""
							SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) FROM information_schema.tables
							WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND table_type = 'BASE TABLE'
							""")) {
				while (rows.next()) {
					tables.add(rows.getString(1));
				}
			}
			for (String table : tables) {
				try (PreparedStatement statement = connection
					.prepareStatement("SELECT count(*) FROM " + table + " t WHERE t::text ILIKE ?")) {
					statement.setString(1, "%" + value + "%");
					try (ResultSet count = statement.executeQuery()) {
						count.next();
						if (count.getLong(1) > 0) {
							holding.add(table);
						}
					}
				}
			}
		}
		assertThat(tables).contains("identity.verification", "identity.school_email_code", "audit.audit_entry",
				"operations.external_operation", "notification.notification");
		return holding;
	}

	private static String otherThan(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private static RequestPostProcessor as(UUID account) {
		return authentication(IdentityTestSupport.authentication(account, null));
	}

}
