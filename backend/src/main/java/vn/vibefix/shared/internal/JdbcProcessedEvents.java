package vn.vibefix.shared.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.ProcessedEvents;

/** Markers in {@code platform.processed_event}, purged by {@link EventPublicationMaintenance}. */
@Component
class JdbcProcessedEvents implements ProcessedEvents {

	private final JdbcClient jdbc;

	private final Clock clock;

	JdbcProcessedEvents(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean markProcessed(String listener, UUID eventId) {
		return this.jdbc.sql("""
				INSERT INTO platform.processed_event (listener, event_id, processed_at)
				VALUES (:listener, :eventId, :at)
				ON CONFLICT DO NOTHING
				""")
			.param("listener", listener)
			.param("eventId", eventId)
			.param("at", Timestamp.from(this.clock.instant()))
			.update() == 1;
	}

}
