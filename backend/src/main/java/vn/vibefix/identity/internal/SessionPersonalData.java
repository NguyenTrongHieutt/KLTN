package vn.vibefix.identity.internal;

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
 * The server-side sessions' part (AD-24, AD-19): an erased account loses every session, found by its
 * principal name (the account ID); the attributes go with them ({@code ON DELETE CASCADE}). It works
 * in any context, with or without the web layer that owns the session repository. A session is
 * delivery state, so an export holds nothing from it.
 */
@Component
class SessionPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	SessionPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("platform.spring_session", "platform.spring_session_attributes");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return Optional.empty();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM platform.spring_session WHERE principal_name = :name")
			.param("name", accountId.toString())
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
