package vn.vibefix.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.FailedOperations;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.OperationTicket;
import vn.vibefix.operations.Outcome;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.ScriptedOperationHandler;

/** AD-6: requesting, joining, claiming, completing and retrying External Operations. */
@RecordApplicationEvents
class ExternalOperationsIT extends IntegrationTest {

	private static final UUID ACCOUNT = UUID.fromString("0190f0c3-0000-7000-8000-00000000a001");

	@Autowired
	private ExternalOperations operations;

	@Autowired
	private FailedOperations failed;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private OperationStore store;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationEvents events;

	@Test
	void aRequestedKeyIsJoinedWhilePendingAndAnsweredWithItsResultOnceItSucceeded() {
		UUID cause = UUID.randomUUID();
		OperationTicket first = request(cause, Map.of("repo", 42));
		OperationTicket second = request(cause, Map.of("repo", 42));

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.status()).isEqualTo(OperationStatus.PENDING);
		assertThat(count()).isOne();

		this.scriptedOperations.answer(new Outcome.Succeeded(Map.of("merged", "abc123")));
		this.worker.runDue(Lane.GITHUB_SHORT);
		OperationTicket third = request(cause, Map.of("repo", 42));

		assertThat(third.status()).isEqualTo(OperationStatus.SUCCEEDED);
		assertThat(third.result()).containsEntry("merged", "abc123");
		assertThat(this.worker.runDue(Lane.GITHUB_SHORT)).isZero();
		assertThat(this.scriptedOperations.runs()).hasSize(1);
	}

	@Test
	void thePollerRunsDueOperationsOnTheLanesOwnThreads() {
		OperationTicket first = request(UUID.randomUUID(), Map.of());
		OperationTicket second = request(UUID.randomUUID(), Map.of());

		this.worker.poll();

		await().atMost(Duration.ofSeconds(10))
			.until(() -> "SUCCEEDED".equals(column(first.id(), "status")) && "SUCCEEDED".equals(column(second.id(), "status")));
		assertThat(this.scriptedOperations.threads()).hasSize(2)
			.allSatisfy(thread -> assertThat(thread).startsWith("operations-github-short-"));
	}

	@Test
	void aKeyNeverChangesItsPayload() {
		UUID cause = UUID.randomUUID();
		request(cause, Map.of("repo", 42));

		assertThatThrownBy(() -> request(cause, Map.of("repo", 43))).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("another payload");
	}

	@Test
	void aRequestNeedsTheCallersTransactionAndAKnownType() {
		assertThatThrownBy(() -> this.operations.request(scripted(UUID.randomUUID(), Map.of())))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> inTransaction(() -> this.operations
			.request(new OperationRequest("test.unknown", UUID.randomUUID(), Map.of(), null, null, 0))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aRolledBackRequestLeavesNothing() {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
			this.operations.request(scripted(UUID.randomUUID(), Map.of()));
			status.setRollbackOnly();
		});

		assertThat(count()).isZero();
	}

	@Test
	void completionWritesTheStatusPurgesPersonalDataAuditsAndPublishesInOneGo() {
		OperationTicket ticket = inTransaction(() -> this.operations.request(new OperationRequest(
				ScriptedOperationHandler.TYPE, UUID.randomUUID(), Map.of("n", 1), Map.of("to", "lan@example.com"),
				ACCOUNT, 0)));

		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.scriptedOperations.runs().getFirst().personalData()).containsEntry("to", "lan@example.com");
		assertThat(column(ticket.id(), "personal_data::text")).isNull();
		assertThat(column(ticket.id(), "status")).isEqualTo("SUCCEEDED");
		assertThat(audit(ticket.id())).containsExactly("operations.operation_succeeded " + ticket.id());
		assertThat(this.events.stream(OperationCompleted.class)).singleElement().satisfies(event -> {
			assertThat(event.operationId()).isEqualTo(ticket.id());
			assertThat(event.key()).isEqualTo(ticket.key());
			assertThat(event.status()).isEqualTo(OperationStatus.SUCCEEDED);
		});
	}

	@Test
	void aTransientFailureRetriesAfter30SecondsDoublingAndFailsAfterTheEighthAttemptAndAFinalReadBack() {
		OperationTicket ticket = request(UUID.randomUUID(), Map.of());
		this.scriptedOperations.answer(transients(8));
		Instant start = this.clock.instant();

		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThat(nextAttemptAt(ticket.id())).isEqualTo(start.plusSeconds(30));
		long waited = 0;
		for (int attempt = 2; attempt <= 8; attempt++) {
			Duration delay = Duration.ofSeconds(30L << (attempt - 2));
			this.clock.advance(delay.minusSeconds(1));
			assertThat(this.worker.runDue(Lane.GITHUB_SHORT)).as("not due before its delay").isZero();
			this.clock.advance(Duration.ofSeconds(1));
			waited += delay.toSeconds();
			assertThat(this.worker.runDue(Lane.GITHUB_SHORT)).isOne();
		}

		assertThat(waited).isEqualTo(30 * 127);
		assertThat(this.scriptedOperations.runs()).hasSize(8);
		assertThat(this.scriptedOperations.readBacks()).hasSize(1);
		assertThat(this.operations.find(ticket.key()).orElseThrow().status()).isEqualTo(OperationStatus.FAILED);
		assertThat(this.failed.findAll()).singleElement().satisfies(failure -> {
			assertThat(failure.operationId()).isEqualTo(ticket.id());
			assertThat(failure.errorCode()).isEqualTo("REMOTE_DOWN");
			assertThat(failure.attempts()).isEqualTo(8);
		});
		assertThat(audit(ticket.id())).containsExactly("operations.operation_failed " + ticket.id());
		assertThat(this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE event_type LIKE '%OperationFailed'")
			.query(Long.class).single()).as("admin's alert is published once").isOne();
	}

	@Test
	void theFinalReadBackTurnsALastFailureIntoSuccessWhenTheEffectHappened() {
		OperationTicket ticket = request(UUID.randomUUID(), Map.of());
		this.scriptedOperations.answer(transients(8)).readBackFinds(Map.of("found", true));

		runEveryAttempt(8);

		assertThat(this.operations.find(ticket.key()).orElseThrow()).satisfies(found -> {
			assertThat(found.status()).isEqualTo(OperationStatus.SUCCEEDED);
			assertThat(found.result()).containsEntry("found", true);
		});
	}

	@Test
	void anAmbiguousAttemptReadsBackBeforeTheNextOne() {
		OperationTicket done = request(UUID.randomUUID(), Map.of());
		this.scriptedOperations.answer(new Outcome.Ambiguous("TIMED_OUT")).readBackFinds(Map.of("found", true));

		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofSeconds(30));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.scriptedOperations.runs()).hasSize(1);
		assertThat(this.scriptedOperations.readBacks()).hasSize(1);
		assertThat(this.operations.find(done.key()).orElseThrow().status()).isEqualTo(OperationStatus.SUCCEEDED);
	}

	@Test
	void anAmbiguousAttemptWhoseEffectIsNotFoundIsTriedAgain() {
		OperationTicket ticket = request(UUID.randomUUID(), Map.of());
		this.scriptedOperations.answer(new Outcome.Ambiguous("TIMED_OUT"));

		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofSeconds(30));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.scriptedOperations.readBacks()).hasSize(1);
		assertThat(this.scriptedOperations.runs()).hasSize(2);
		assertThat(this.operations.find(ticket.key()).orElseThrow().status()).isEqualTo(OperationStatus.SUCCEEDED);
	}

	@Test
	void abortedAndObsoleteEndAtOnceWithTheirCode() {
		OperationTicket aborted = request(UUID.randomUUID(), Map.of());
		this.scriptedOperations.answer(new Outcome.Aborted("REPO_GONE"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		OperationTicket obsolete = request(UUID.randomUUID(), Map.of());
		this.scriptedOperations.answer(new Outcome.Obsolete("JOB_MOVED_ON"));
		this.worker.runDue(Lane.GITHUB_SHORT);
		this.clock.advance(Duration.ofHours(1));

		assertThat(this.worker.runDue(Lane.GITHUB_SHORT)).isZero();
		assertThat(this.operations.find(aborted.key()).orElseThrow()).satisfies(found -> {
			assertThat(found.status()).isEqualTo(OperationStatus.ABORTED);
			assertThat(found.errorCode()).isEqualTo("REPO_GONE");
		});
		assertThat(this.operations.find(obsolete.key()).orElseThrow()).satisfies(found -> {
			assertThat(found.status()).isEqualTo(OperationStatus.OBSOLETE);
			assertThat(found.errorCode()).isEqualTo("JOB_MOVED_ON");
		});
		assertThat(audit(aborted.id())).containsExactly("operations.operation_aborted " + aborted.id());
		assertThat(audit(obsolete.id())).containsExactly("operations.operation_obsolete " + obsolete.id());
	}

	@Test
	void aDeferredAttemptWaitsWithoutUsingUpAnAttempt() {
		OperationTicket ticket = request(UUID.randomUUID(), Map.of());
		Instant until = this.clock.instant().plus(Duration.ofHours(3));
		this.scriptedOperations.answer(new Outcome.Deferred(until));

		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(column(ticket.id(), "status")).isEqualTo("PENDING");
		assertThat(column(ticket.id(), "attempt_count::text")).isEqualTo("0");
		assertThat(nextAttemptAt(ticket.id())).isEqualTo(until);
	}

	@Test
	void anExpiredLeaseIsClaimedAgainAndReadsBackFirst() {
		OperationTicket ticket = request(UUID.randomUUID(), Map.of());
		// A worker claimed it and stopped before completing.
		this.jdbc.sql("""
				UPDATE operations.external_operation
				SET status = 'RUNNING', attempt_count = 1, lease_until = :lease WHERE id = :id
				""").param("lease", Timestamp.from(this.clock.instant().plusSeconds(30))).param("id", ticket.id()).update();

		assertThat(this.worker.runDue(Lane.GITHUB_SHORT)).as("its lease still runs").isZero();
		this.clock.advance(Duration.ofSeconds(31));
		this.worker.runDue(Lane.GITHUB_SHORT);

		assertThat(this.scriptedOperations.readBacks()).singleElement()
			.satisfies(run -> assertThat(run.attempt()).isEqualTo(2));
		assertThat(this.scriptedOperations.runs()).hasSize(1);
		assertThat(column(ticket.id(), "status")).isEqualTo("SUCCEEDED");
	}

	@Test
	void aWorkerThatLostItsLeaseChangesNothing() {
		OperationTicket ticket = request(UUID.randomUUID(), Map.of());
		Instant now = this.clock.instant();
		OperationStore.Claimed stale = this.store
			.claim(Lane.GITHUB_SHORT, List.of(ScriptedOperationHandler.TYPE), 1, now, now.plusSeconds(30))
			.getFirst();
		OperationStore.Claimed current = this.store
			.claim(Lane.GITHUB_SHORT, List.of(ScriptedOperationHandler.TYPE), 1, now.plusSeconds(31), now.plusSeconds(61))
			.getFirst();

		assertThat(this.store.complete(stale, OperationStatus.SUCCEEDED, Map.of(), null, now)).isFalse();
		assertThat(this.store.retryLater(stale, now, "LATE", false)).isFalse();
		assertThat(this.store.complete(current, OperationStatus.SUCCEEDED, Map.of(), null, now)).isTrue();
		assertThat(audit(ticket.id())).hasSize(1);
	}

	@Test
	void dueOperationsAreClaimedByPriority() {
		OperationTicket reminder = request(UUID.randomUUID(), Map.of(), 2);
		OperationTicket code = request(UUID.randomUUID(), Map.of(), 1);
		OperationTicket urgent = request(UUID.randomUUID(), Map.of(), 0);
		Instant now = this.clock.instant();

		List<UUID> order = List.of(claimOne(now), claimOne(now), claimOne(now));

		assertThat(order).containsExactly(urgent.id(), code.id(), reminder.id());
	}

	@Test
	void theDatabaseKeepsThePayloadAndFinalStatusesAndLetsPersonalDataOnlyBeCleared() {
		OperationTicket ticket = inTransaction(() -> this.operations.request(new OperationRequest(
				ScriptedOperationHandler.TYPE, UUID.randomUUID(), Map.of("n", 1), Map.of("to", "a@example.com"),
				ACCOUNT, 0)));

		assertThatThrownBy(() -> update("payload = '{\"n\": 2}'", ticket.id())).hasMessageContaining("never change");
		assertThatThrownBy(() -> update("personal_data = '{\"to\": \"b@example.com\"}'", ticket.id()))
			.hasMessageContaining("only be cleared");
		update("personal_data = NULL", ticket.id());
		this.worker.runDue(Lane.GITHUB_SHORT);
		assertThatThrownBy(() -> update("status = 'PENDING', completed_at = NULL", ticket.id()))
			.hasMessageContaining("final");
	}

	private OperationTicket request(UUID cause, Map<String, Object> payload) {
		return request(cause, payload, 0);
	}

	private OperationTicket request(UUID cause, Map<String, Object> payload, int priority) {
		return inTransaction(() -> this.operations.request(
				new OperationRequest(ScriptedOperationHandler.TYPE, cause, payload, null, null, priority)));
	}

	private static OperationRequest scripted(UUID cause, Map<String, Object> payload) {
		return new OperationRequest(ScriptedOperationHandler.TYPE, cause, payload, null, null, 0);
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return new TransactionTemplate(this.transactionManager).execute(status -> work.get());
	}

	private UUID claimOne(Instant now) {
		OperationStore.Claimed claimed = this.store
			.claim(Lane.GITHUB_SHORT, List.of(ScriptedOperationHandler.TYPE), 1, now, now.plusSeconds(30))
			.getFirst();
		this.store.complete(claimed, OperationStatus.SUCCEEDED, Map.of(), null, now);
		return claimed.id();
	}

	private void runEveryAttempt(int attempts) {
		for (int attempt = 1; attempt <= attempts; attempt++) {
			this.worker.runDue(Lane.GITHUB_SHORT);
			this.clock.advance(Duration.ofHours(1));
		}
	}

	private static Outcome[] transients(int count) {
		Outcome[] outcomes = new Outcome[count];
		java.util.Arrays.fill(outcomes, new Outcome.Transient("REMOTE_DOWN"));
		return outcomes;
	}

	private long count() {
		return this.jdbc.sql("SELECT count(*) FROM operations.external_operation").query(Long.class).single();
	}

	private String column(UUID id, String expression) {
		return this.jdbc.sql("SELECT " + expression + " AS value FROM operations.external_operation WHERE id = :id")
			.param("id", id)
			.query((row, number) -> row.getString("value"))
			.list()
			.getFirst();
	}

	private Instant nextAttemptAt(UUID id) {
		return this.jdbc.sql("SELECT next_attempt_at FROM operations.external_operation WHERE id = :id")
			.param("id", id)
			.query(Timestamp.class)
			.single()
			.toInstant();
	}

	private void update(String assignment, UUID id) {
		this.jdbc.sql("UPDATE operations.external_operation SET " + assignment + " WHERE id = :id")
			.param("id", id)
			.update();
	}

	/** The audit entries naming the operation, as "action correlationId". */
	private List<String> audit(UUID operationId) {
		return this.jdbc.sql("""
				SELECT entry.action || ' ' || entry.correlation_id FROM audit.audit_entry entry
				JOIN audit.audit_entry_object object ON object.entry_id = entry.id
				WHERE object.object_type = 'operations.external_operation' AND object.object_id = :id
				""").param("id", operationId).query(String.class).list();
	}

}
