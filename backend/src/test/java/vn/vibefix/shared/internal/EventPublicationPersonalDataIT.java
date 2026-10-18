package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;

/**
 * AD-24, AD-9: completed event publications naming an erased account or a deleted Product go at
 * once; incomplete ones still reach their listener first.
 */
class EventPublicationPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private EventPublicationPersonalData participant;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		publication(SUBJECT, true);
		publication(OTHER, true);
		return OTHER.toString();
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM platform.event_publication
				WHERE completion_date IS NOT NULL AND strpos(serialized_event, :id) > 0
				""").param("id", accountId.toString()).query(Long.class).single() > 0;
	}

	@Test
	void anIncompletePublicationIsKeptForItsListener() {
		publication(SUBJECT, false);

		this.participant.eraseAccount(SUBJECT);

		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.event_publication").query(Long.class).single())
			.isOne();
	}

	@Test
	void productDeletionRemovesCompletedPublicationsNamingTheProduct() {
		UUID product = UUID.fromString("0190f0c3-0000-7000-8000-00000000e003");
		publication(product, true);
		publication(OTHER, true);

		this.participant.deleteProduct(product);

		assertThat(holdsErasableDataOf(product)).isFalse();
		assertThat(holdsErasableDataOf(OTHER)).isTrue();
	}

	@Test
	void theRegistryBlocksNoErasureAndExportsNothing() {
		publication(SUBJECT, true);

		assertThat(this.participant.erasureBlockers(SUBJECT)).isEmpty();
		assertThat(this.participant.export(SUBJECT)).isEmpty();
	}

	private void publication(UUID named, boolean completed) {
		Timestamp at = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO platform.event_publication
				    (id, listener_id, event_type, serialized_event, publication_date, completion_date, status, completion_attempts)
				VALUES (:id, 'probe.listener', 'probe.Event', :event, :at, :completedAt, :status, 1)
				""")
			.param("id", UUID.randomUUID())
			.param("event", "{\"accountId\":\"" + named + "\"}")
			.param("at", at)
			.param("completedAt", completed ? at : null)
			.param("status", completed ? "COMPLETED" : "PUBLISHED")
			.update();
	}

}
