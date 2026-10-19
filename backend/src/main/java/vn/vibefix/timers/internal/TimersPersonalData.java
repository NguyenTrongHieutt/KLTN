package vn.vibefix.timers.internal;

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
 * Deadlines' part in AD-24. A Deadline whose subject is an account (a Student verification's reminder
 * and expiry) names that account and nothing else; it is no record a person reads, so an export holds
 * none of it, and an erasure deletes the account's Deadlines, whose rules no longer apply.
 */
@Component
class TimersPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	TimersPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("timers.deadline");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return Optional.empty();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM timers.deadline WHERE subject_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		// A Product's Deadlines belong to its Jobs; the owning modules end them with the Product.
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
