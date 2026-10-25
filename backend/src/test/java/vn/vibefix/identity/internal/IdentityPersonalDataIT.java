package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;

/**
 * AD-24: {@code identity} exports the account's own data and erases its GitHub identity and kept
 * token, keeping the row and the acknowledgments as the record of what it agreed to.
 */
class IdentityPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	private static final String CODE_HASH = "c0de".repeat(16);

	@Autowired
	private IdentityPersonalData participant;

	@Autowired
	private Accounts accounts;

	@Autowired
	private TokenCipher cipher;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		seed(SUBJECT, 7001, "lan-subject");
		seed(OTHER, 7002, "other-person");
		return "other-person";
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM identity.account
				WHERE id = :id AND (github_user_id IS NOT NULL OR github_email IS NOT NULL)
				""").param("id", accountId).query(Long.class).single() > 0
				|| this.accounts.customerToken(accountId).isPresent()
				|| this.jdbc.sql("""
						SELECT (SELECT count(*) FROM identity.customer_profile WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.school_email_code WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.student_verification WHERE account_id = :id)
						""").param("id", accountId).query(Long.class).single() > 0;
	}

	@Test
	void anExportHoldsTheRoleTheGitHubIdentityAndTheAcknowledgments() {
		seedSubjectAndOther();

		PersonalDataSection section = this.participant.export(SUBJECT).orElseThrow();

		assertThat(section.module()).isEqualTo("identity");
		assertThat(section.data()).containsEntry("role", "CUSTOMER")
			.containsEntry("githubLogin", "lan-subject")
			.containsEntry("githubEmail", "lan-subject@example.test");
		assertThat(section.data().toString()).contains("PRIVACY")
			.contains("2.0")
			.contains("lan-subject@contact.test")
			.contains("lan-subject@hust.edu.vn")
			.contains("lan-subject@sis.hust.edu.vn")
			.doesNotContain("ciphertext")
			.doesNotContain(CODE_HASH);
	}

	@Test
	void anErasureKeepsTheRowAndItsAcknowledgments() {
		seedSubjectAndOther();

		this.participant.eraseAccount(SUBJECT);

		Account erased = this.accounts.findById(SUBJECT).orElseThrow();
		assertThat(erased.erasedAt()).isNotNull();
		assertThat(erased.githubLogin()).isNull();
		assertThat(erased.isActive()).isFalse();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment WHERE account_id = :id")
			.param("id", SUBJECT)
			.query(Long.class)
			.single()).isOne();
		assertThat(this.participant.erasureBlockers(SUBJECT)).isEmpty();
	}

	@Test
	void aGoogleAccountExportsItsGoogleEmailAndNameButNeverItsSubjectAndErasureClearsAllThree() {
		UUID google = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-sub-7003", "3.0");

		PersonalDataSection section = this.participant.export(google).orElseThrow();

		assertThat(section.data()).containsEntry("kind", "USER")
			.containsEntry("googleEmail", "google-sub-7003@gmail.test")
			.containsEntry("googleName", "Người dùng google-sub-7003")
			.doesNotContainKey("role")
			.doesNotContainKey("githubEmail");
		assertThat(section.data().values().stream().map(String::valueOf)).as("the subject is a key, never exported")
			.doesNotContain("google-sub-7003");

		this.participant.eraseAccount(google);

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM identity.account
				WHERE id = :id AND google_sub IS NULL AND google_email IS NULL AND google_name IS NULL AND erased_at IS NOT NULL
				""").param("id", google).query(Long.class).single()).isOne();
		assertThat(this.accounts.notificationAddress(google)).isEmpty();
	}

	private void seed(UUID id, long githubId, String login) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO identity.account (id, kind, github_user_id, github_login, github_email, role, created_at)
				VALUES (:id, 'USER', :githubId, :login, :login || '@example.test', 'CUSTOMER', :now)
				""").param("id", id).param("githubId", githubId).param("login", login).param("now", now).update();
		this.accounts.insertAcknowledgment(UUID.randomUUID(), id, "PRIVACY", "2.0", this.clock.instant());
		this.accounts.storeCustomerToken(id, this.cipher.seal("gho_" + login, id.toString()), this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO identity.customer_profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:id, 'INDIVIDUAL', :login, :login || '@contact.test', :now)
				""").param("id", id).param("login", login).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO identity.school_email_code (id, account_id, email, code_hash, sent_at, expires_at)
				VALUES (:code, :id, :login || '@sis.hust.edu.vn', :hash, :now, :now)
				""")
			.param("code", UUID.randomUUID())
			.param("id", id)
			.param("login", login)
			.param("hash", CODE_HASH)
			.param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO identity.student_verification (account_id, method, school_email, verified_at, valid_until)
				VALUES (:id, 'SCHOOL_EMAIL', :login || '@hust.edu.vn', :now, DATE '2026-09-30')
				""").param("id", id).param("login", login).param("now", now).update();
		assertThat(Role.valueOf("CUSTOMER")).isEqualTo(Role.CUSTOMER);
	}

}
