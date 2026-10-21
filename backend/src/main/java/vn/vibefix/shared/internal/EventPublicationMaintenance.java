package vn.vibefix.shared.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.shared.StalledEventPublications;
import vn.vibefix.shared.EventPublicationsStalled;
import vn.vibefix.shared.IdGenerator;

/**
 * Keeps Spring Modulith's event publication registry (AD-9). Modulith counts the first delivery as
 * attempt 1 in {@code completion_attempts}, and each resubmission adds one. Republishing on restart is Modulith's
 * own ({@code spring.modulith.events.republish-outstanding-events-on-restart}); this class adds the
 * rest, run by {@link EventPublicationScheduler}:
 * <ul>
 * <li>an incomplete publication whose last attempt is at least {@code resubmit-after} old is
 * resubmitted, until it has had {@code max-attempts} attempts;</li>
 * <li>after its last attempt it is marked stalled in {@code platform.event_publication_stalled} for
 * the Administrator queue, with a warning in the log, and is never resubmitted automatically;</li>
 * <li>completed publications are purged after {@code purge-after}, replay markers after
 * {@code processed-retention}, and stalled marks once their publication completed or is gone.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(EventPublicationProperties.class)
class EventPublicationMaintenance implements StalledEventPublications {

	private static final Logger log = LoggerFactory.getLogger(EventPublicationMaintenance.class);

	private final IncompleteEventPublications incomplete;

	private final CompletedEventPublications completed;

	private final JdbcClient jdbc;

	private final Clock clock;

	private final EventPublicationProperties properties;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	EventPublicationMaintenance(IncompleteEventPublications incomplete, CompletedEventPublications completed,
			JdbcClient jdbc, Clock clock, EventPublicationProperties properties, ApplicationEventPublisher events,
			PlatformTransactionManager transactionManager, IdGenerator ids) {
		this.events = events;
		this.transactions = new TransactionTemplate(transactionManager);
		this.ids = ids;
		this.incomplete = incomplete;
		this.completed = completed;
		this.jdbc = jdbc;
		this.clock = clock;
		this.properties = properties;
	}

	/** Resubmits every incomplete publication that is due and has attempts left. */
	void resubmitDue() {
		Instant cutoff = this.clock.instant().minus(this.properties.resubmitAfter());
		this.incomplete.resubmitIncompletePublications(
				publication -> publication.getCompletionAttempts() < this.properties.maxAttempts()
						&& !lastTry(publication).isAfter(cutoff));
	}

	/** Marks the publications whose last attempt failed; returns how many were newly marked. */
	int markStalled() {
		Instant now = this.clock.instant();
		int marked = this.jdbc.sql("""
				INSERT INTO platform.event_publication_stalled (publication_id, stalled_at)
				SELECT id, :now FROM platform.event_publication
				WHERE completion_date IS NULL
				  AND COALESCE(completion_attempts, 1) >= :maxAttempts
				  AND COALESCE(last_resubmission_date, publication_date) <= :cutoff
				ON CONFLICT DO NOTHING
				""")
			.param("now", Timestamp.from(now))
			.param("maxAttempts", this.properties.maxAttempts())
			.param("cutoff", Timestamp.from(now.minus(this.properties.resubmitAfter())))
			.update();
		if (marked > 0) {
			log.warn("{} event publication(s) stalled after {} attempts; they wait in the Administrator queue",
					marked, this.properties.maxAttempts());
			// AD-9: every Administrator is alerted, through a publication of its own.
			this.transactions.executeWithoutResult(
					status -> this.events.publishEvent(new EventPublicationsStalled(this.ids.newId(), marked)));
		}
		return marked;
	}

	/** Purges completed publications, old replay markers, and stalled marks that no longer apply. */
	void purgeCompleted() {
		this.completed.deletePublicationsOlderThan(this.properties.purgeAfter());
		this.jdbc.sql("DELETE FROM platform.processed_event WHERE processed_at < :cutoff")
			.param("cutoff", Timestamp.from(this.clock.instant().minus(this.properties.processedRetention())))
			.update();
		this.jdbc.sql("""
				DELETE FROM platform.event_publication_stalled stalled
				WHERE NOT EXISTS (SELECT 1 FROM platform.event_publication publication
				                  WHERE publication.id = stalled.publication_id AND publication.completion_date IS NULL)
				""").update();
	}

	@Override
	public List<StalledPublication> findAll() {
		return this.jdbc.sql("""
				SELECT publication.id, publication.event_type, publication.listener_id, publication.publication_date,
				       COALESCE(publication.completion_attempts, 1) AS attempts, stalled.stalled_at
				FROM platform.event_publication_stalled stalled
				JOIN platform.event_publication publication ON publication.id = stalled.publication_id
				ORDER BY publication.publication_date, publication.id
				""")
			.query((row, number) -> new StalledPublication(row.getObject("id", UUID.class),
					row.getString("event_type"), row.getString("listener_id"),
					row.getTimestamp("publication_date").toInstant(), row.getInt("attempts"),
					row.getTimestamp("stalled_at").toInstant()))
			.list();
	}

	private static Instant lastTry(EventPublication publication) {
		Instant resubmitted = publication.getLastResubmissionDate();
		return resubmitted != null ? resubmitted : publication.getPublicationDate();
	}

}
