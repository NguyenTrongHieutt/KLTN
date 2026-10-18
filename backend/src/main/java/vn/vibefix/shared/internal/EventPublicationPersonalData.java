package vn.vibefix.shared.internal;

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
 * The event publication registry's part (AD-24, AD-9). Serialized events may name accounts and
 * Products. A completed publication naming the erased ID is deleted at once; an incomplete one must
 * still reach its listener, so it stays until it completes and is purged 30 days later. The
 * registry is internal delivery state, so an export holds nothing from it.
 */
@Component
class EventPublicationPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	EventPublicationPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("platform.event_publication");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		return Optional.empty();
	}

	@Override
	public void eraseAccount(UUID accountId) {
		deleteCompletedNaming(accountId);
	}

	@Override
	public void deleteProduct(UUID productId) {
		deleteCompletedNaming(productId);
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

	private void deleteCompletedNaming(UUID id) {
		this.jdbc.sql("""
				DELETE FROM platform.event_publication
				WHERE completion_date IS NOT NULL AND strpos(serialized_event, :id) > 0
				""").param("id", id.toString()).update();
	}

}
