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
				|| this.accounts.customerToken(accountId).isPresent();
	}

	@Test
	void anExportHoldsTheRoleTheGitHubIdentityAndTheAcknowledgments() {
		seedSubjectAndOther();

		PersonalDataSection section = this.participant.export(SUBJECT).orElseThrow();

		assertThat(section.module()).isEqualTo("identity");
		assertThat(section.data()).containsEntry("role", "CUSTOMER")
			.containsEntry("githubLogin", "lan-subject")
			.containsEntry("githubEmail", "lan-subject@example.test");
		assertThat(section.data().toString()).contains("PRIVACY").contains("2.0").doesNotContain("ciphertext");
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

	private void seed(UUID id, long githubId, String login) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO identity.account (id, github_user_id, github_login, github_email, role, created_at)
				VALUES (:id, :githubId, :login, :login || '@example.test', 'CUSTOMER', :now)
				""").param("id", id).param("githubId", githubId).param("login", login).param("now", now).update();
		this.accounts.insertAcknowledgment(UUID.randomUUID(), id, "PRIVACY", "2.0", this.clock.instant());
		this.accounts.storeCustomerToken(id, this.cipher.seal("gho_" + login, id.toString()), this.clock.instant());
		assertThat(Role.valueOf("CUSTOMER")).isEqualTo(Role.CUSTOMER);
	}

}
