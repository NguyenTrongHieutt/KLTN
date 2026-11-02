package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.shared.CrewSplit;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The one executor of a final split (AD-9, AD-15, AD-33): in one transaction, under only the ledger account lock, the
 * Milestone's unreversed RELEASE_POOLED splits by {@link CrewSplit} into one RELEASE per non-zero part, with one
 * POOL_RELEASED of the whole pool, so paid out does not change. Each payee gets {@value #RELEASED}, reached by the split
 * however long the Job's row has been gone. A final split with no unreversed pool, with parties other than
 * {@code job.splitPartiesOf} names, or whose parts do not sum to the pool writes nothing: it goes to FR-64's list and
 * alerts Administrators as a {@value SplitPoolRefusedOperation#TYPE} operation. A replay writes nothing.
 */
@Component
class SplitPoolExecution {

	static final String RELEASED = "payment.funds_released";

	/** The entry a final split reverses. */
	private record Pool(UUID id, long amountVnd) {
	}

	private final JdbcClient jdbc;

	private final MilestoneSplits splits;

	private final Notifications notifications;

	private final ExternalOperations operations;

	private final ProcessedEvents processedEvents;

	private final IdGenerator ids;

	private final Clock clock;

	SplitPoolExecution(JdbcClient jdbc, MilestoneSplits splits, Notifications notifications, ExternalOperations operations,
			ProcessedEvents processedEvents, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.splits = splits;
		this.notifications = notifications;
		this.operations = operations;
		this.processedEvents = processedEvents;
		this.ids = ids;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onMilestoneSplitFinalized(MilestoneSplitFinalized event) {
		if (!this.processedEvents.markProcessed("payment.SplitPoolExecution.onMilestoneSplitFinalized", event.eventId())) {
			return;
		}
		UUID productId = lockAccount(event.milestoneId()).orElse(null);
		Pool pool = productId == null ? null : unreversedPool(event.milestoneId()).orElse(null);
		if (pool == null) {
			refuse(event, "NO_POOL");
			return;
		}
		if (!partiesOf(event.milestoneId()).equals(event.percentages().keySet())) {
			refuse(event, "PARTIES_DIFFER");
			return;
		}
		Map<UUID, Long> parts = parts(event, pool);
		if (parts == null) {
			refuse(event, "PARTS_OFF");
			return;
		}
		parts.forEach((party, amount) -> release(event, productId, pool, party, amount));
		reverse(event, productId, pool);
		parts.forEach((party, amount) -> tell(event, party, amount));
	}

	private Optional<UUID> lockAccount(UUID milestoneId) {
		return this.jdbc.sql("SELECT product_id FROM payment.ledger_account WHERE milestone_id = :id FOR UPDATE")
			.param("id", milestoneId)
			.query(UUID.class)
			.optional();
	}

	private Optional<Pool> unreversedPool(UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT e.id, e.amount_vnd FROM payment.ledger_entry e
				WHERE e.milestone_id = :id AND e.entry_type = 'RELEASE_POOLED'
				  AND NOT EXISTS (SELECT 1 FROM payment.ledger_entry r WHERE r.reverses_entry_id = e.id)
				""")
			.param("id", milestoneId)
			.query((rs, n) -> new Pool(rs.getObject("id", UUID.class), rs.getLong("amount_vnd")))
			.optional();
	}

	/** The parties the release fixed, never the RELEASE rows (AD-15). */
	private Set<UUID> partiesOf(UUID milestoneId) {
		Set<UUID> parties = new HashSet<>();
		this.splits.splitPartiesOf(milestoneId).forEach(party -> parties.add(party.accountId()));
		return parties;
	}

	/** The parts by {@link CrewSplit}, or {@code null} when the percentages cannot split the pool exactly. */
	private static Map<UUID, Long> parts(MilestoneSplitFinalized event, Pool pool) {
		try {
			Map<UUID, Long> parts = CrewSplit.split(pool.amountVnd(), event.percentages(), event.mentorId());
			return parts.values().stream().mapToLong(Long::longValue).sum() == pool.amountVnd() ? parts : null;
		}
		catch (IllegalArgumentException | ArithmeticException off) {
			return null;
		}
	}

	private void release(MilestoneSplitFinalized event, UUID productId, Pool pool, UUID party, long amount) {
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, split_version_no, share_percent, base_amount_vnd, created_at)
				VALUES (:id, :key, 'RELEASE', :job, :product, :milestone, :amount, :party, :version, :percent, :base, :now)
				""")
			.param("id", this.ids.newId())
			.param("key", "RELEASE:" + event.milestoneSplitId() + ":" + party)
			.param("job", event.jobId())
			.param("product", productId)
			.param("milestone", event.milestoneId())
			.param("amount", amount)
			.param("party", party)
			.param("version", event.finalVersionNo())
			.param("percent", event.percentages().get(party))
			.param("base", pool.amountVnd())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private void reverse(MilestoneSplitFinalized event, UUID productId, Pool pool) {
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    reverses_entry_id, created_at)
				VALUES (:id, :key, 'POOL_RELEASED', :job, :product, :milestone, :amount, :reverses, :now)
				""")
			.param("id", this.ids.newId())
			.param("key", "POOL_RELEASED:" + pool.id())
			.param("job", event.jobId())
			.param("product", productId)
			.param("milestone", event.milestoneId())
			.param("amount", pool.amountVnd())
			.param("reverses", pool.id())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	private void tell(MilestoneSplitFinalized event, UUID party, long amount) {
		Audience audience = party.equals(event.mentorId()) ? Audience.MENTOR : Audience.SPLIT_PARTY;
		this.notifications.send(new NotificationRequest(RELEASED, event.milestoneSplitId(), List.of(new Recipient(party, audience)),
				Map.of("amount", PaymentOrders.vnd(amount), "job", event.jobTitle(), "n", String.valueOf(event.milestoneNumber())),
				"/so-du", event.jobId(), null, event.milestoneId()));
	}

	/** The split's one refusal: nothing moves, and an Administrator looks at it (FR-64). */
	private void refuse(MilestoneSplitFinalized event, String reason) {
		this.operations.request(new OperationRequest(SplitPoolRefusedOperation.TYPE, event.milestoneSplitId(),
				Map.of("milestoneSplitId", event.milestoneSplitId().toString(), "jobId", event.jobId().toString(), "milestoneId",
						event.milestoneId().toString(), "reason", reason),
				null, null, 0));
	}

}
