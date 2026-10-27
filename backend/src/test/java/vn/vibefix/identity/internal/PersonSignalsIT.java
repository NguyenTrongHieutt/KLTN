package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.PersonSignalMatched;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.identity.internal.JdbcPersonSignals.Source;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * AD-19, D1: person-link signals are HMACs only, match one person across accounts and sources,
 * publish a match once, and outlive an erasure by 24 months.
 */
@RecordApplicationEvents
class PersonSignalsIT extends IntegrationTest {

	@Autowired
	private JdbcPersonSignals signals;

	@Autowired
	private PersonSignals publicSignals;

	@Autowired
	private GitHubLinksImpl links;

	@Autowired
	private IdentityPersonalData personalData;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEvents events;

	@Test
	void aSchoolEmailMatchesAnotherAccountsGitHubEmailWithoutKeepingTheAddress() {
		UUID customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);

		record(customer, Source.GITHUB_EMAIL, "Lan.Nguyen@Example.test");
		assertThat(this.events.stream(PersonSignalMatched.class)).isEmpty();
		record(student, Source.SCHOOL_EMAIL, "  lan.nguyen@example.test ");

		assertThat(this.publicSignals.sharesPersonSignal(student, customer)).isTrue();
		assertThat(this.publicSignals.sharesPersonSignal(customer, student)).isTrue();
		assertThat(this.events.stream(PersonSignalMatched.class)).singleElement().satisfies(event -> {
			assertThat(event.accountId()).isEqualTo(student);
			assertThat(event.otherAccountId()).isEqualTo(customer);
			assertThat(event.kind()).isEqualTo("EMAIL");
		});
		assertThat(this.jdbc.sql("SELECT string_agg(hmac || source, ',') FROM identity.person_signal")
			.query(String.class)
			.single()).doesNotContain("lan").doesNotContain("example");
	}

	@Test
	void aLinkedInProfileIsItsHandleInAnyLetterCaseAndADifferentKindNeverMatches() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);

		record(mentor, Source.LINKEDIN, "https://www.linkedin.com/in/Do-Manh-Hung/");
		record(student, Source.LINKEDIN, "https://www.linkedin.com/in/do-manh-hung");
		record(other, Source.GITHUB_EMAIL, "do-manh-hung@example.test");

		assertThat(this.publicSignals.sharesPersonSignal(mentor, student)).isTrue();
		assertThat(this.publicSignals.sharesPersonSignal(mentor, other)).isFalse();
		record(student, Source.LINKEDIN, "https://www.linkedin.com/in/do-manh-hung");
		assertThat(this.events.stream(PersonSignalMatched.class)).as("a signal already recorded publishes nothing")
			.hasSize(1);
		record(student, Source.LINKEDIN, "https://evil.example/in/do-manh-hung");
		assertThat(count(student)).isOne();
	}

	@Test
	void everyVerifiedGitHubEmailIsASignalAtLinking() {
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		long githubId = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id")
			.param("id", mentor)
			.query(Long.class)
			.single();

		this.links.link(mentor, new GitHubLinkReader.LinkedUser(githubId, "hung-dev", null, "hung@example.test",
				List.of("hung@example.test", "hung@work.example.test")), GitHubLinksImpl.Purpose.PROFILE, null);

		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id AND source = 'GITHUB_EMAIL'")
			.param("id", mentor)
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	@Test
	void anErasedAccountsSignalsStayTwentyFourMonths() {
		UUID gone = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID back = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		record(gone, Source.GITHUB_EMAIL, "same@example.test");

		this.transactions.executeWithoutResult(status -> this.personalData.eraseAccount(gone));
		record(back, Source.GITHUB_EMAIL, "same@example.test");
		assertThat(this.publicSignals.sharesPersonSignal(back, gone)).as("still matched after the erasure").isTrue();

		this.clock.setInstant(this.clock.instant().atOffset(ZoneOffset.UTC).plusMonths(24).minusMinutes(1).toInstant());
		this.poller.runDue();
		assertThat(count(gone)).isOne();

		this.clock.advance(Duration.ofMinutes(2));
		this.poller.runDue();
		assertThat(count(gone)).isZero();
		assertThat(count(back)).isOne();
	}

	/** Story 19.4: an erased account's verification rows keep their subject HMACs as long as its signals. */
	@Test
	void anErasedAccountsVerificationRowsGoWithItsSignalsAfterTwentyFourMonths() {
		UUID gone = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		IdentityTestSupport.verifyStudent(this.jdbc, gone, java.time.LocalDate.parse("2026-09-30"));
		IdentityTestSupport.verifyStudent(this.jdbc, other, java.time.LocalDate.parse("2026-09-30"));

		this.transactions.executeWithoutResult(status -> this.personalData.eraseAccount(gone));
		assertThat(verifications(gone)).as("ended ERASED, its HMAC kept").isOne();

		this.clock.setInstant(this.clock.instant().atOffset(ZoneOffset.UTC).plusMonths(24).minusMinutes(1).toInstant());
		this.poller.runDue();
		assertThat(verifications(gone)).isOne();

		this.clock.advance(Duration.ofMinutes(2));
		this.poller.runDue();
		assertThat(verifications(gone)).isZero();
		assertThat(verifications(other)).isOne();
	}

	private long verifications(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM identity.verification WHERE account_id = :id")
			.param("id", account)
			.query(Long.class)
			.single();
	}

	private void record(UUID account, Source source, String value) {
		this.transactions.executeWithoutResult(status -> this.signals.record(account, source, value));
	}

	private long count(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM identity.person_signal WHERE account_id = :id")
			.param("id", account)
			.query(Long.class)
			.single();
	}

}
