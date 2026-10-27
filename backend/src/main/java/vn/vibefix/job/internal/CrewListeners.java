package vn.vibefix.job.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.PersonSignalMatched;
import vn.vibefix.shared.ProcessedEvents;

/**
 * AD-19: a person-link signal two accounts newly share alerts every Administrator once for each
 * running Job both take part in, as its Customer, its Mentor, or a Crew member; nothing else changes
 * (replay-safe through {@link ProcessedEvents}). A lock carries through to every Job the person holds a relation
 * to, Crew and party alike, in the marker's transaction ({@link LockConsequences}, Stories 10.2, 20.3 and 20.4),
 * once per event.
 */
@Component
class CrewListeners {

	private final JdbcClient jdbc;

	private final CrewAlerts alerts;

	private final ProcessedEvents processedEvents;

	private final LockConsequences locks;

	private final Clock clock;

	CrewListeners(JdbcClient jdbc, CrewAlerts alerts, ProcessedEvents processedEvents, LockConsequences locks,
			Clock clock) {
		this.jdbc = jdbc;
		this.alerts = alerts;
		this.processedEvents = processedEvents;
		this.locks = locks;
		this.clock = clock;
	}

	/** The windows end at the lock's own time; an event stored before Story 20.4 carries none, so it ends now. */
	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		if (!this.processedEvents.markProcessed("job.CrewListeners.onAccountLocked", event.eventId())) {
			return;
		}
		Instant lockedAt = event.lockedAt() != null ? event.lockedAt() : this.clock.instant();
		this.locks.locked(event.eventId(), event.accountId(), lockedAt);
	}

	@ApplicationModuleListener
	void onPersonSignalMatched(PersonSignalMatched event) {
		if (!this.processedEvents.markProcessed("job.CrewListeners.onPersonSignalMatched", event.eventId())) {
			return;
		}
		Map<UUID, String> first = roles(event.accountId());
		Map<UUID, String> second = roles(event.otherAccountId());
		for (Map.Entry<UUID, String> job : first.entrySet()) {
			String other = second.get(job.getKey());
			if (other != null) {
				String title = this.jdbc.sql("SELECT title FROM job.job WHERE id = :id").param("id", job.getKey())
					.query(String.class).single();
				this.alerts.personLink(UUID.nameUUIDFromBytes((event.eventId() + ":" + job.getKey()).getBytes()), job.getKey(),
						title, job.getValue(), other);
			}
		}
	}

	/** The running Jobs the account takes part in, with its role there: CUSTOMER, MENTOR, or CREW. */
	private Map<UUID, String> roles(UUID accountId) {
		Map<UUID, String> roles = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT id, CASE WHEN customer_account_id = :id THEN 'CUSTOMER' ELSE 'MENTOR' END AS role FROM job.job
				WHERE (customer_account_id = :id OR mentor_account_id = :id)
				  AND state IN ('SELECTING', 'AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING')
				UNION
				SELECT r.job_id, 'CREW' FROM job.crew_row r JOIN job.job j ON j.id = r.job_id
				WHERE r.member_account_id = :id AND r.status <> 'RELEASED'
				  AND j.state IN ('AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING')
				""").param("id", accountId).query(rs -> {
			roles.put(rs.getObject("id", UUID.class), rs.getString("role"));
		});
		return roles;
	}

}
