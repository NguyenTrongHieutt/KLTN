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
				WHERE id = :id AND (github_user_id IS NOT NULL OR github_email IS NOT NULL OR github_linked_at IS NOT NULL)
				""").param("id", accountId).query(Long.class).single() > 0
				|| this.accounts.customerToken(accountId).isPresent()
				|| this.jdbc.sql("""
						SELECT (SELECT count(*) FROM identity.profile WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.profile_link WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.showcase_repository WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.school_email_code WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.school_email_send_hour WHERE account_id = :id)
						     + (SELECT count(*) FROM identity.verification WHERE account_id = :id
						                                                     AND (ended_at IS NULL OR facts IS NOT NULL))
						""").param("id", accountId).query(Long.class).single() > 0;
	}

	@Test
	void anExportHoldsTheRoleTheGitHubIdentityAndTheAcknowledgments() {
		seedSubjectAndOther();

		PersonalDataSection section = this.participant.export(SUBJECT).orElseThrow();

		assertThat(section.module()).isEqualTo("identity");
		assertThat(section.data()).containsEntry("role", "CUSTOMER")
			.containsEntry("githubLogin", "lan-subject")
			.containsEntry("githubEmail", "lan-subject@example.test")
			.containsEntry("githubLinkedAt", this.clock.instant().toString());
		assertThat(section.data().toString()).contains("PRIVACY")
			.contains("2.0")
			.contains("lan-subject@contact.test")
			.contains("https://lan-subject.dev")
			.contains("lan-subject/shop")
			.contains("Trường của lan-subject")
			.contains("Kinh nghiệm của lan-subject")
			.contains("yearsOfExperience=4")
			.contains("+84 912 345 678")
			.contains("skills=[React]")
			.contains("Ngành của lan-subject")
			.contains("verifiedMonth=2026-01")
			.contains("method=SCHOOL_EMAIL")
			.contains("school=hust.edu.vn")
			.doesNotContain("ciphertext")
			.doesNotContain(CODE_HASH)
			.doesNotContain(IdentityTestSupport.fakeHmac("lan-subject"))
			.doesNotContain(IdentityTestSupport.fakeHmac("code-lan-subject"));
	}

	/** The verification row keeps its subject HMAC after erasure (AD-24); its facts go, and it no longer counts. */
	@Test
	void anErasureEndsTheLiveVerificationErasedAndKeepsItsHmac() {
		seedSubjectAndOther();

		this.participant.eraseAccount(SUBJECT);

		assertThat(this.jdbc.sql("""
				SELECT ended_reason || ' ' || (facts IS NULL) || ' ' || subject_hmac FROM identity.verification
				WHERE account_id = :id
				""").param("id", SUBJECT).query(String.class).single())
			.isEqualTo("ERASED true " + IdentityTestSupport.fakeHmac("lan-subject"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.verification WHERE account_id = :id AND ended_at IS NULL")
			.param("id", OTHER).query(Long.class).single()).isOne();
	}

	@Test
	void anErasureKeepsTheRowAndItsAcknowledgments() {
		seedSubjectAndOther();

		this.participant.eraseAccount(SUBJECT);

		Account erased = this.accounts.findById(SUBJECT).orElseThrow();
		assertThat(erased.erasedAt()).isNotNull();
		assertThat(erased.githubLogin()).isNull();
		assertThat(this.accounts.gitHubLinkedAt(SUBJECT)).as("the link's time goes too").isEmpty();
		assertThat(erased.isActive()).isFalse();
		assertThat(this.jdbc.sql("SELECT count(*) FROM identity.acknowledgment WHERE account_id = :id")
			.param("id", SUBJECT)
			.query(Long.class)
			.single()).isOne();
		assertThat(this.participant.erasureBlockers(SUBJECT)).isEmpty();
	}

	@Test
	void aGoogleAccountExportsItsGoogleSubjectEmailAndNameAndErasureClearsAllThree() {
		UUID google = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-sub-7003", "3.1");

		PersonalDataSection section = this.participant.export(google).orElseThrow();

		assertThat(section.data()).containsEntry("kind", "USER")
			.containsEntry("googleSub", "google-sub-7003")
			.containsEntry("googleEmail", "google-sub-7003@gmail.test")
			.containsEntry("googleName", "Người dùng google-sub-7003")
			.doesNotContainKey("role")
			.doesNotContainKey("githubEmail");

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
				INSERT INTO identity.account (id, kind, github_user_id, github_login, github_email, github_linked_at, role,
				    created_at)
				VALUES (:id, 'USER', :githubId, :login, :login || '@example.test', :now, 'CUSTOMER', :now)
				""").param("id", id).param("githubId", githubId).param("login", login).param("now", now).update();
		this.accounts.insertAcknowledgment(UUID.randomUUID(), id, "PRIVACY", "2.0", this.clock.instant());
		this.accounts.storeCustomerToken(id, this.cipher.seal("gho_" + login, id.toString()), this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, phone, experience,
				    years_of_experience, skill_ids, skill_names, student_school, updated_at)
				VALUES (:id, 'INDIVIDUAL', :login, :login || '@contact.test', '+84 912 345 678', 'Kinh nghiệm của ' || :login,
				        4, ARRAY[CAST('0192b000-0000-7000-8001-000000000004' AS UUID)], 'React', 'Trường của ' || :login, :now)
				""").param("id", id).param("login", login).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO identity.profile_link (account_id, position, url) VALUES (:id, 0, 'https://' || :login || '.dev')
				""").param("id", id).param("login", login).update();
		this.jdbc.sql("""
				INSERT INTO identity.showcase_repository (account_id, position, owner, name) VALUES (:id, 0, :login, 'shop')
				""").param("id", id).param("login", login).update();
		this.jdbc.sql("""
				INSERT INTO identity.school_email_code (id, account_id, address_hmac, key_version, address_hmacs, code_hash,
				    sent_at, expires_at, school, declared_name, declared_major, renewal, consent_version)
				VALUES (:code, :id, :hmac, 1, jsonb_build_object('1', CAST(:hmac AS text)), :hash, :now, :now, 'hust.edu.vn',
				        'Tên của ' || :login, 'Ngành của ' || :login, FALSE, '1.0')
				""")
			.param("code", UUID.randomUUID())
			.param("id", id)
			.param("hmac", IdentityTestSupport.fakeHmac("code-" + login))
			.param("login", login)
			.param("hash", CODE_HASH)
			.param("now", now)
			.update();
		this.jdbc.sql("INSERT INTO identity.school_email_send_hour (account_id, hour, sends) VALUES (:id, :now, 1)")
			.param("id", id)
			.param("now", now)
			.update();
		IdentityTestSupport.verifyStudent(this.jdbc, id, java.time.LocalDate.parse("2026-09-30"),
				IdentityTestSupport.fakeHmac(login));
		assertThat(Role.valueOf("CUSTOMER")).isEqualTo(Role.CUSTOMER);
	}

}
