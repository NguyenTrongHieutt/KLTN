package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;

/** AD-24, AD-19: erasing an account ends every session it has, and nobody else's. */
class SessionPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private SessionPersonalData participant;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		session(SUBJECT);
		session(SUBJECT);
		session(OTHER);
		return OTHER.toString();
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("SELECT count(*) FROM platform.spring_session WHERE principal_name = :name")
			.param("name", accountId.toString())
			.query(Long.class)
			.single() > 0;
	}

	@Test
	void theAttributesOfAnErasedSessionGoWithIt() {
		seedSubjectAndOther();

		this.participant.eraseAccount(SUBJECT);

		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.spring_session_attributes").query(Long.class).single())
			.isOne();
	}

	private void session(UUID principal) {
		String id = UUID.randomUUID().toString();
		this.jdbc.sql("""
				INSERT INTO platform.spring_session
				    (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name)
				VALUES (:id, :sessionId, 0, 0, 1800, 9999999999999, :principal)
				""").param("id", id).param("sessionId", UUID.randomUUID().toString()).param("principal", principal.toString())
			.update();
		this.jdbc.sql("""
				INSERT INTO platform.spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
				VALUES (:id, 'probe', :bytes)
				""").param("id", id).param("bytes", new byte[] { 1 }).update();
	}

}
