package vn.vibefix.operations.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * External Operations' part in AD-24. A pending operation's personal data (an email address and its
 * parameters, for example) is what its handler needs, cleared when the operation completes; it is
 * nothing a person reads, so an export holds none of it. An erasure clears it at once, and the
 * handler then finds the operation obsolete.
 */
@Component
class OperationsPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	OperationsPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("operations.external_operation");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return Optional.empty();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("""
				UPDATE operations.external_operation SET personal_data = NULL, account_id = NULL
				WHERE account_id = :accountId
				""").param("accountId", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		// Operations name Products only by causing record; their payloads hold no Product content.
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
