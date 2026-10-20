package vn.vibefix.settings.internal;

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
 * {@code settings.change} names the Administrator who saved it (FR-63). It is the platform's record,
 * not the Administrator's data, so it is not exported; an erasure keeps the change and drops the name.
 */
@Component
class SettingsPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	SettingsPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("settings.change");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return Optional.empty();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("UPDATE settings.change SET changed_by = NULL WHERE changed_by = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
