package vn.vibefix.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.PersonalDataParticipantContract;
import vn.vibefix.support.ScriptedOperationHandler;

/** AD-24: erasing an account clears its pending operations' personal data, and nobody else's. */
class OperationsPersonalDataIT extends IntegrationTest implements PersonalDataParticipantContract {

	@Autowired
	private OperationsPersonalData participant;

	@Autowired
	private ExternalOperations operations;

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
		request(SUBJECT, "subject@example.com");
		request(OTHER, "other-marker@example.com");
		return "other-marker@example.com";
	}

	@Override
	public boolean holdsErasableDataOf(UUID accountId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM operations.external_operation
				WHERE account_id = :accountId OR personal_data->>'owner' = :owner
				""").param("accountId", accountId).param("owner", accountId.toString()).query(Long.class).single() > 0;
	}

	@Test
	void anErasureLeavesThePayloadAndTheOperationItself() {
		seedSubjectAndOther();

		this.participant.eraseAccount(SUBJECT);

		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation WHERE personal_data IS NULL")
			.query(Long.class)
			.single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM operations.external_operation").query(Long.class).single())
			.isEqualTo(2);
	}

	private void request(UUID account, String address) {
		new TransactionTemplate(this.transactionManager)
			.executeWithoutResult(status -> this.operations.request(new OperationRequest(ScriptedOperationHandler.TYPE,
					UUID.randomUUID(), Map.of("kind", "test"), Map.of("to", address, "owner", account.toString()), account,
					0)));
	}

}
