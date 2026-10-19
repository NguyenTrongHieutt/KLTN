package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/** FR-5, AD-34, UX-DR19: the school email code, its limits, and the verification it grants. */
class SchoolEmailVerificationIT extends IntegrationTest {

	private static final Pattern CODE = Pattern.compile("Mã xác minh vibefix của bạn: ([0-9]{6}) \\(hết hạn sau 10 phút\\)");

	private static final String MINH = "minh@sis.hust.edu.vn";

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

	private UUID student;

	/** The dev seed, which integration tests load, allowlists {@code hust.edu.vn}. */
	@BeforeEach
	void aStudent() {
		this.student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
	}

	@Test
	void anAllowlistedAddressGetsASixDigitCodeThatIsKeptOnlyAsAHash() throws Exception {
		send(this.student, MINH).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.pendingCode.email").value(MINH))
			.andExpect(jsonPath("$.pendingCode.attemptsLeft").value(5))
			.andExpect(jsonPath("$.pendingCode.expired").value(false));

		String code = deliveredCode(MINH);
		RecordingMailSender.Sent sent = this.mail.sent().getLast();
		assertThat(sent.text()).contains(code).contains("hết hạn sau 10 phút");
		List<Map<String, Object>> rows = this.jdbc.sql("SELECT * FROM identity.school_email_code").query().listOfRows();
		assertThat(rows).singleElement().satisfies(row -> assertThat(row.values().toString()).doesNotContain(code));
	}

	@Test
	void theDomainIsComparedWithoutLetterCaseAndASubdomainIsAllowed() throws Exception {
		send(this.student, "Minh@SIS.Hust.EDU.vn").andExpect(status().isAccepted())
			.andExpect(jsonPath("$.pendingCode.email").value(MINH));
	}

	@Test
	void anAddressOutsideTheAllowlistIsRefusedAndNothingIsSent() throws Exception {
		for (String address : new String[] { "khoa@gmail.com", "khoa@evilhust.edu.vn", "khoa@hust.edu.vn.evil.com" }) {
			send(this.student, address).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
				.andExpect(jsonPath("$.reason").value("EMAIL_DOMAIN_NOT_ALLOWED"));
		}
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).isEmpty();
	}

	@Test
	void anAddressThatVerifiesAnotherAccountIsRefused() throws Exception {
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		send(other, MINH).andExpect(status().isAccepted());
		confirm(other, deliveredCode(MINH)).andExpect(status().isOk());

		send(this.student, MINH).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.reason").value("ALREADY_USED_FOR_VERIFICATION"));
	}

	@Test
	void anAccountGetsThreeSendsPerHour() throws Exception {
		Instant first = this.clock.instant();
		for (int i = 0; i < 3; i++) {
			send(this.student, MINH).andExpect(status().isAccepted());
			this.clock.advance(Duration.ofMinutes(5));
		}

		send(this.student, MINH).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
			.andExpect(header().string("Retry-After",
					Long.toString(Duration.between(this.clock.instant(), first.plus(Duration.ofHours(1))).toSeconds())));

		this.clock.setInstant(first.plus(Duration.ofHours(1)).plusSeconds(1));
		send(this.student, MINH).andExpect(status().isAccepted());
	}

	@Test
	void anAddressGetsTenSendsPerUtcDay() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T01:00:00Z"));
		for (int i = 0; i < 10; i++) {
			if (i > 0 && i % 3 == 0) {
				this.clock.advance(Duration.ofMinutes(61));
			}
			send(this.student, MINH).andExpect(status().isAccepted());
		}
		this.clock.advance(Duration.ofMinutes(61));

		send(this.student, MINH).andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", Long.toString(
					Duration.between(this.clock.instant(), Instant.parse("2026-10-20T00:00:00Z")).toSeconds())));
		assertThat(this.jdbc.sql("SELECT sends FROM identity.school_email_send_day").query(Integer.class).single())
			.as("the refused send is not counted").isEqualTo(10);

		this.clock.setInstant(Instant.parse("2026-10-20T00:00:01Z"));
		send(this.student, MINH).andExpect(status().isAccepted());
	}

	@Test
	void aWrongCodeCountsAndTheViewSaysHowManyAttemptsAreLeft() throws Exception {
		send(this.student, MINH);
		String wrong = otherThan(deliveredCode(MINH));

		for (int i = 0; i < 2; i++) {
			confirm(this.student, wrong).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
				.andExpect(jsonPath("$.reason").value("WRONG_CODE"));
		}

		view(this.student).andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.pendingCode.attemptsLeft").value(3));
	}

	@Test
	void aCodeExpiresAfterTenMinutes() throws Exception {
		send(this.student, MINH);
		String code = deliveredCode(MINH);
		this.clock.advance(Duration.ofMinutes(10).plusSeconds(1));

		confirm(this.student, code).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CODE_EXPIRED"));
		view(this.student).andExpect(jsonPath("$.status").value("UNVERIFIED"))
			.andExpect(jsonPath("$.pendingCode.expired").value(true));
	}

	@Test
	void theSixthAttemptIsRefusedEvenWithTheRightCode() throws Exception {
		send(this.student, MINH);
		String code = deliveredCode(MINH);
		for (int i = 0; i < 5; i++) {
			confirm(this.student, otherThan(code)).andExpect(jsonPath("$.reason").value("WRONG_CODE"));
		}
		view(this.student).andExpect(jsonPath("$.pendingCode.attemptsLeft").value(0));

		confirm(this.student, code).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("TOO_MANY_ATTEMPTS"));
		assertThat(this.studentVerification.isVerified(this.student)).isFalse();
	}

	@Test
	void concurrentEntriesNeverPassFiveAttempts() throws Exception {
		send(this.student, MINH);
		String wrong = otherThan(deliveredCode(MINH));
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Future<Confirmation>> results = new ArrayList<>();
		try {
			for (int i = 0; i < 8; i++) {
				Callable<Confirmation> entry = () -> this.verifications.confirm(this.student, wrong);
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
		send(this.student, MINH);
		String first = deliveredCode(MINH);
		this.clock.advance(Duration.ofMinutes(1));
		send(this.student, MINH);
		String second = deliveredCode(MINH);

		if (!first.equals(second)) {
			confirm(this.student, first).andExpect(jsonPath("$.reason").value("WRONG_CODE"));
		}
		confirm(this.student, second).andExpect(status().isOk());
	}

	@Test
	void theRightCodeVerifiesUntilTheNextThirtiethOfSeptemberAuditsAndClosesTheToDo() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T03:00:00Z"));
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> this.todos
			.open(new TodoRequest(SignIns.VERIFY_STUDENT, this.student, Audience.SUBJECT, this.student, Map.of(),
					"/ho-so", null, null)));
		send(this.student, MINH);

		confirm(this.student, deliveredCode(MINH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("VERIFIED"))
			.andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"))
			.andExpect(jsonPath("$.schoolEmail").value(MINH))
			.andExpect(jsonPath("$.pendingCode").doesNotExist());

		assertThat(this.studentVerification.isVerified(this.student)).isTrue();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'identity.student_verified' AND actor_account_id = :id
				""").param("id", this.student).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT closed_at FROM notification.todo WHERE todo_key = 'identity.verify_student' AND account_id = :id
				""").param("id", this.student).query(Timestamp.class).single()).isNotNull();
		send(this.student, MINH).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").doesNotExist());
		confirm(this.student, "123456").andExpect(status().isConflict());
	}

	@Test
	void theVerificationEndsAfterItsThirtiethOfSeptemberInVietnam() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T03:00:00Z"));
		send(this.student, MINH);
		confirm(this.student, deliveredCode(MINH)).andExpect(status().isOk());

		this.clock.setInstant(Instant.parse("2027-09-30T16:59:59Z"));
		assertThat(this.studentVerification.isVerified(this.student)).as("23:59:59 on 30/09 in Vietnam").isTrue();
		this.clock.setInstant(Instant.parse("2027-09-30T17:00:00Z"));
		assertThat(this.studentVerification.isVerified(this.student)).as("00:00 on 01/10 in Vietnam").isFalse();
		view(this.student).andExpect(jsonPath("$.status").value("EXPIRED"))
			.andExpect(jsonPath("$.verifiedUntil").value("2027-09-30"));
		// An expired Student verifies again.
		send(this.student, MINH).andExpect(status().isAccepted());
	}

	@Test
	void thePurgeDeletesCodesADayOldAndPastDaysCounters() throws Exception {
		this.clock.setInstant(Instant.parse("2026-10-19T01:00:00Z"));
		send(this.student, MINH);
		this.clock.setInstant(Instant.parse("2026-10-20T00:30:00Z"));
		send(this.student, MINH);

		this.clock.setInstant(Instant.parse("2026-10-20T01:00:00Z"));
		assertThat(this.purge.purge()).as("the first code and the 19/10 counter").isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT sent_at FROM identity.school_email_code").query(Timestamp.class).single())
			.isEqualTo(Timestamp.from(Instant.parse("2026-10-20T00:30:00Z")));
		assertThat(this.jdbc.sql("SELECT day FROM identity.school_email_send_day").query(LocalDate.class).single())
			.isEqualTo(LocalDate.of(2026, 10, 20));
		view(this.student).andExpect(jsonPath("$.pendingCode.email").value(MINH));
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
		send(this.student, "not-an-email").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("email"));
		confirm(this.student, "12ab56").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("code"));
		confirm(this.student, "123456").andExpect(status().isNotFound());
	}

	@Test
	void onlyAStudentHasAVerification() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);

		this.mvc.perform(get("/api/v1/verification").with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		this.mvc.perform(post("/api/v1/verification/school-email").with(as(lan, Role.CUSTOMER))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + MINH + "\"}")).andExpect(status().isNotFound());
		this.worker.runDue(Lane.EMAIL);
		assertThat(this.mail.sent()).isEmpty();
	}

	private ResultActions send(UUID account, String email) throws Exception {
		return this.mvc.perform(post("/api/v1/verification/school-email").with(as(account, Role.STUDENT))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\"}"));
	}

	private ResultActions confirm(UUID account, String code) throws Exception {
		return this.mvc.perform(post("/api/v1/verification/school-email/confirm").with(as(account, Role.STUDENT))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\"}"));
	}

	private ResultActions view(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/verification").with(as(account, Role.STUDENT)));
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

	private static String otherThan(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private static RequestPostProcessor as(UUID account, Role role) {
		return authentication(IdentityTestSupport.authentication(account, role));
	}

}
