package vn.vibefix.timers.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;
import vn.vibefix.support.ScriptedDeadlineHandler;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/** AD-24: an erasure deletes the Deadlines whose subject is the account; an export holds none. */
class TimersPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private TimersPersonalData participant;

	@Autowired
	private Deadlines deadlines;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Override
	public PersonalDataParticipant participant() {
		return this.participant;
	}

	@Override
	public String seedSubjectAndOther() {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
			for (UUID account : new UUID[] { SUBJECT, OTHER }) {
				this.deadlines.schedule(new DeadlineRequest(ScriptedDeadlineHandler.PLAIN.key(), account,
						"generation-of-" + account, null, this.clock.instant().plus(Duration.ofDays(1))));
			}
		});
		return "generation-of-" + OTHER;
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("SELECT count(*) FROM timers.deadline WHERE subject_id = :id")
			.param("id", accountId)
			.query(Long.class)
			.single() > 0;
	}

	@Test
	void anExportHoldsNoDeadline() {
		seedSubjectAndOther();

		assertThat(this.participant.export(SUBJECT)).isEmpty();
		assertThat(this.participant.erasureBlockers(SUBJECT)).isEmpty();
	}

}
