package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import eventfixtures.ProbeEvent;
import eventfixtures.ProbeListener;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.shared.StalledEventPublications;
import vn.vibefix.shared.StalledEventPublications.StalledPublication;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-9: a failed listener is resubmitted once its last attempt is 5 minutes old; after 5 attempts
 * its publication is marked stalled for the Administrator queue; a replay changes nothing; and
 * completed publications are purged after 30 days. Time is the test clock, which the registry
 * reads too. {@code EventRepublishOnRestartIT} covers the restart.
 */
@Import(ProbeListener.class)
class EventPublicationIT extends IntegrationTest {

	private static final Duration WAIT = Duration.ofSeconds(20);

	@Autowired
	private EventPublicationMaintenance maintenance;

	@Autowired
	private StalledEventPublications stalled;

	@Autowired
	private ProcessedEvents processed;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void resetListener() {
		ProbeListener.reset(0);
	}

	@Test
	void aFailedListenerIsResubmittedAfterFiveMinutesAndAppliesOnce() {
		ProbeListener.reset(1);
		publish(new ProbeEvent(UUID.randomUUID(), "once"));
		awaitStatus("FAILED", 1);

		this.maintenance.resubmitDue();
		assertThat(ProbeListener.attempts.get()).as("not yet due").isOne();

		this.clock.advance(Duration.ofMinutes(5));
		this.maintenance.resubmitDue();

		awaitStatus("COMPLETED", 2);
		assertThat(ProbeListener.applied.get()).as("the failed attempt rolled its marker back").isOne();
	}

	@Test
	void fiveFailedAttemptsMarkThePublicationStalled() {
		ProbeListener.reset(Integer.MAX_VALUE);
		publish(new ProbeEvent(UUID.randomUUID(), "never"));
		awaitStatus("FAILED", 1);

		for (int attempt = 2; attempt <= 5; attempt++) {
			this.clock.advance(Duration.ofMinutes(5));
			this.maintenance.resubmitDue();
			awaitStatus("FAILED", attempt);
		}
		assertThat(this.maintenance.markStalled()).as("the last attempt is not 5 minutes old yet").isZero();

		this.clock.advance(Duration.ofMinutes(5));
		this.maintenance.resubmitDue();
		assertThat(this.maintenance.markStalled()).isOne();
		assertThat(this.maintenance.markStalled()).as("marked once").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE event_type LIKE '%EventPublicationsStalled'")
			.query(Long.class).single()).as("admin's alert is published once").isOne();

		assertThat(ProbeListener.attempts.get()).isEqualTo(5);
		assertThat(publication().get("completion_attempts")).isEqualTo(5);
		List<StalledPublication> queue = this.stalled.findAll();
		assertThat(queue).singleElement().satisfies(entry -> {
			assertThat(entry.eventType()).isEqualTo(ProbeEvent.class.getName());
			assertThat(entry.attempts()).isEqualTo(5);
			assertThat(entry.stalledAt()).isEqualTo(this.clock.instant());
		});

		this.clock.advance(Duration.ofHours(1));
		this.maintenance.resubmitDue();
		assertThat(ProbeListener.attempts.get()).as("a stalled publication is never resubmitted").isEqualTo(5);
	}

	/**
	 * A replay is the same publication delivered again after its effect committed, for example when
	 * the application stopped before the registry marked it completed.
	 */
	@Test
	void aReplayOfTheSameEventChangesNothing() {
		publish(new ProbeEvent(UUID.randomUUID(), "twice"));
		awaitStatus("COMPLETED", 1);
		this.jdbc.sql("UPDATE platform.event_publication SET completion_date = NULL, status = 'FAILED'").update();

		this.clock.advance(Duration.ofMinutes(5));
		this.maintenance.resubmitDue();

		awaitStatus("COMPLETED", 2);
		assertThat(ProbeListener.attempts.get()).isEqualTo(2);
		assertThat(ProbeListener.applied.get()).isOne();
	}

	@Test
	void markingNeedsTheListenersTransaction() {
		assertThatThrownBy(() -> this.processed.markProcessed("x", UUID.randomUUID()))
			.isInstanceOf(IllegalTransactionStateException.class);
		boolean[] marks = new boolean[2];
		UUID eventId = UUID.randomUUID();
		this.transactions.executeWithoutResult(status -> marks[0] = this.processed.markProcessed("x", eventId));
		this.transactions.executeWithoutResult(status -> marks[1] = this.processed.markProcessed("x", eventId));

		assertThat(marks).containsExactly(true, false);
	}

	@Test
	void completedPublicationsArePurgedAfterThirtyDaysAndOldMarkersAfterNinety() {
		Instant now = this.clock.instant();
		UUID old = completedPublication(now.minus(Duration.ofDays(31)));
		UUID recent = completedPublication(now.minus(Duration.ofDays(29)));
		this.jdbc.sql("INSERT INTO platform.event_publication_stalled VALUES (:id, :at)")
			.param("id", recent)
			.param("at", Timestamp.from(now))
			.update();
		marker("old", now.minus(Duration.ofDays(91)));
		marker("kept", now.minus(Duration.ofDays(89)));

		this.maintenance.purgeCompleted();

		assertThat(this.jdbc.sql("SELECT id FROM platform.event_publication").query(UUID.class).list())
			.containsExactly(recent)
			.doesNotContain(old);
		assertThat(this.jdbc.sql("SELECT listener FROM platform.processed_event").query(String.class).list())
			.containsExactly("kept");
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.event_publication_stalled").query(Long.class).single())
			.as("a completed publication is no longer stalled")
			.isZero();
	}

	private void publish(ProbeEvent event) {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(event));
	}

	private void awaitStatus(String status, int completionAttempts) {
		await().atMost(WAIT).untilAsserted(() -> {
			Map<String, Object> publication = publication();
			assertThat(publication.get("status")).isEqualTo(status);
			assertThat(publication.get("completion_attempts")).isEqualTo(completionAttempts);
		});
	}

	private Map<String, Object> publication() {
		return this.jdbc.sql("SELECT status, completion_attempts FROM platform.event_publication WHERE event_type = :type")
			.param("type", ProbeEvent.class.getName())
			.query()
			.singleRow();
	}

	private UUID completedPublication(Instant completedAt) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO platform.event_publication
				    (id, listener_id, event_type, serialized_event, publication_date, completion_date, status, completion_attempts)
				VALUES (:id, 'probe.listener', 'probe.Event', '{}', :at, :at, 'COMPLETED', 1)
				""").param("id", id).param("at", Timestamp.from(completedAt)).update();
		return id;
	}

	private void marker(String listener, Instant at) {
		this.jdbc.sql("INSERT INTO platform.processed_event VALUES (:listener, :id, :at)")
			.param("listener", listener)
			.param("id", UUID.randomUUID())
			.param("at", Timestamp.from(at))
			.update();
	}

}
