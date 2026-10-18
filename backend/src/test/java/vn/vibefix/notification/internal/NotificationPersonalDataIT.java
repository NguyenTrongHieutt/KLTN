package vn.vibefix.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;

/** AD-24: an account exports its own notifications and to-dos, and erasure deletes them. */
class NotificationPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private NotificationPersonalData participant;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		seed(SUBJECT, "subject-marker");
		seed(OTHER, "other-marker-91c2");
		return "other-marker-91c2";
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("""
				SELECT (SELECT count(*) FROM notification.notification WHERE account_id = :id)
				     + (SELECT count(*) FROM notification.todo WHERE account_id = :id)
				""").param("id", accountId).query(Long.class).single() > 0;
	}

	@Test
	void anExportHoldsTheAccountsNotificationsAndToDos() {
		seedSubjectAndOther();

		PersonalDataSection section = this.participant.export(SUBJECT).orElseThrow();

		assertThat(section.module()).isEqualTo("notification");
		assertThat(section.data().toString()).contains("subject-marker").contains("test.subject_info").contains("test.other");
		assertThat(this.participant.export(UUID.randomUUID())).isEmpty();
	}

	private void seed(UUID account, String marker) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at)
				VALUES (:id, :account, 'test.subject_info', :cause, jsonb_build_object('name', CAST(:marker AS text)), '/a', :now)
				""")
			.param("id", UUID.randomUUID())
			.param("account", account)
			.param("cause", UUID.randomUUID())
			.param("marker", marker)
			.param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, opened_at)
				VALUES (:id, 'test.other', :account, :account, 3, '{}', '/b', :now)
				""").param("id", UUID.randomUUID()).param("account", account).param("now", now).update();
	}

}
