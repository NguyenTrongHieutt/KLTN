package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.job.MilestoneSplitFinalized;
import vn.vibefix.job.MilestoneSplits;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code delivery}'s part in exports, erasure, and Product deletion (AD-24): a Mentor exports the
 * handovers they made with their marks and notes, and a Customer the checks they decided with; erasing
 * an account clears it as the handover's Mentor or decider (the handover stays, without a person);
 * deleting a Product deletes its handovers. A Mentor's screenshot uploads are theirs (Story 8.3): erasure
 * deletes those no handover carries and clears them as uploader of the others; deleting a Product deletes
 * them all, files included. A running Job blocks erasure through {@code job}.
 * <p>
 * While a Milestone Split of a Job is not final, its Handover Commit authors stay as evidence a Split Resolution reads,
 * with the handover rows they hang from (AD-24, A-01): the rest goes, a row in {@code delivery.split_deferral} says what
 * waits, and the listener on {@link MilestoneSplitFinalized} finishes it once the Job has no open split.
 */
@Component
class DeliveryPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	private final StoredFiles files;

	private final MilestoneSplits splits;

	private final Clock clock;

	DeliveryPersonalData(JdbcClient jdbc, StoredFiles files, MilestoneSplits splits, Clock clock) {
		this.jdbc = jdbc;
		this.files = files;
		this.splits = splits;
		this.clock = clock;
	}

	@Override
	public Set<String> tables() {
		return Set.of("delivery.handover", "delivery.handover_criterion", "delivery.handover_item_note",
				"delivery.handover_check", "delivery.follow_up", "delivery.handover_commit", "delivery.handover_sensitive_change",
				"delivery.handover_page", "delivery.handover_screenshot", "delivery.handover_acceptance");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> handovers = this.jdbc.sql("""
				SELECT id, job_id, milestone_number, round, created_at FROM delivery.handover
				WHERE mentor_account_id = :id ORDER BY seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> handover = new LinkedHashMap<>();
			UUID id = rs.getObject("id", UUID.class);
			handover.put("jobId", rs.getObject("job_id", UUID.class).toString());
			handover.put("milestone", rs.getInt("milestone_number"));
			handover.put("round", rs.getInt("round"));
			handover.put("at", rs.getTimestamp("created_at").toInstant().toString());
			handover.put("criteria", this.jdbc.sql("""
					SELECT number, met, note FROM delivery.handover_criterion WHERE handover_id = :id ORDER BY number
					""").param("id", id).query((row, m) -> {
				Map<String, Object> criterion = new LinkedHashMap<>();
				criterion.put("number", row.getInt("number"));
				criterion.put("met", row.getBoolean("met"));
				criterion.put("note", row.getString("note"));
				return criterion;
			}).list());
			handover.put("itemNotes", this.jdbc.sql("""
					SELECT item_key, note FROM delivery.handover_item_note WHERE handover_id = :id ORDER BY item_key
					""").param("id", id).query((row, m) -> Map.<String, Object>of("key", row.getString("item_key"), "note",
					row.getString("note"))).list());
			return handover;
		}).list();
		List<Map<String, Object>> decisions = this.jdbc.sql("""
				SELECT h.id, h.job_id, h.milestone_number, h.status, h.decided_at FROM delivery.handover h
				WHERE h.decided_by = :id ORDER BY h.seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> decision = new LinkedHashMap<>();
			decision.put("jobId", rs.getObject("job_id", UUID.class).toString());
			decision.put("milestone", rs.getInt("milestone_number"));
			decision.put("decision", rs.getString("status"));
			decision.put("at", rs.getTimestamp("decided_at").toInstant().toString());
			decision.put("checks", this.jdbc.sql("""
					SELECT met, comment FROM delivery.handover_check WHERE handover_id = :id
					""").param("id", rs.getObject("id", UUID.class)).query((row, m) -> {
				Map<String, Object> mark = new LinkedHashMap<>();
				mark.put("met", row.getBoolean("met"));
				if (row.getString("comment") != null) {
					mark.put("comment", row.getString("comment"));
				}
				return mark;
			}).list());
			return decision;
		}).list();
		if (handovers.isEmpty() && decisions.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("delivery", Map.of("handovers", handovers, "decisions", decisions)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("SELECT file_id FROM delivery.handover_screenshot WHERE account_id = :id AND handover_id IS NULL")
			.param("id", accountId)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
		this.jdbc.sql("DELETE FROM delivery.handover_screenshot WHERE account_id = :id AND handover_id IS NULL")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.handover_screenshot SET account_id = NULL WHERE account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.handover SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.handover SET decided_by = NULL WHERE decided_by = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE delivery.handover_acceptance SET customer_account_id = NULL WHERE customer_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.follow_up SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		deleteOrDefer(productId, "DELETE");
	}

	/**
	 * NFR-10: what each handover said goes (criteria and notes, the Customer's marks and comments,
	 * screenshots, recorded commits, Pages, Sensitive Changes, pending acceptances, follow-ups); each
	 * handover row and its status stay for the Milestone history.
	 */
	@Override
	public void retireJobData(UUID productId) {
		deleteOrDefer(productId, "RETIRE");
	}

	/**
	 * What each handover said goes for every Job of the Product. The recorded commits, and on a deletion the handover rows,
	 * go too, unless an open split makes them wait.
	 */
	private void deleteOrDefer(UUID productId, String waiting) {
		Set<UUID> open = this.splits.openSplitJobs(productId);
		open.forEach(job -> defer(job, productId, waiting));
		this.jdbc.sql("DELETE FROM delivery.follow_up WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("SELECT file_id FROM delivery.handover_screenshot WHERE product_id = :id")
			.param("id", productId)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
		this.jdbc.sql("DELETE FROM delivery.handover_screenshot WHERE product_id = :id").param("id", productId).update();
		String handovers = "SELECT id FROM delivery.handover WHERE product_id = :id";
		for (String table : List.of("delivery.handover_criterion", "delivery.handover_item_note", "delivery.handover_check",
				"delivery.handover_sensitive_change", "delivery.handover_page", "delivery.handover_acceptance")) {
			this.jdbc.sql("DELETE FROM " + table + " WHERE handover_id IN (" + handovers + ")").param("id", productId).update();
		}
		this.jdbc.sql("SELECT DISTINCT job_id FROM delivery.handover WHERE product_id = :id")
			.param("id", productId)
			.query(UUID.class)
			.list()
			.stream()
			.filter(job -> !open.contains(job))
			.forEach(job -> finish(job, waiting));
	}

	/** The Job's last split is final: what waited goes. A replay finds no deferral. */
	@ApplicationModuleListener
	void onMilestoneSplitFinalized(MilestoneSplitFinalized event) {
		String waiting = this.jdbc.sql("SELECT waiting FROM delivery.split_deferral WHERE job_id = :job FOR UPDATE")
			.param("job", event.jobId())
			.query(String.class)
			.optional()
			.orElse(null);
		if (waiting == null || this.splits.hasOpenSplit(event.jobId())) {
			return;
		}
		finish(event.jobId(), waiting);
		this.jdbc.sql("DELETE FROM delivery.split_deferral WHERE job_id = :job").param("job", event.jobId()).update();
	}

	/** The Job's recorded commits and, on a deletion, its handover rows. */
	private void finish(UUID jobId, String waiting) {
		this.jdbc.sql("DELETE FROM delivery.handover_commit WHERE handover_id IN (SELECT id FROM delivery.handover WHERE job_id = :job)")
			.param("job", jobId)
			.update();
		if ("DELETE".equals(waiting)) {
			this.jdbc.sql("DELETE FROM delivery.handover WHERE job_id = :job").param("job", jobId).update();
		}
	}

	/** Records what waits for the Job's open split; a deletion outranks a retirement. */
	private void defer(UUID jobId, UUID productId, String waiting) {
		this.jdbc.sql("""
				INSERT INTO delivery.split_deferral (job_id, product_id, waiting, deferred_at) VALUES (:job, :product, :waiting, :now)
				ON CONFLICT (job_id) DO UPDATE SET waiting = CASE WHEN EXCLUDED.waiting = 'DELETE' THEN 'DELETE'
				    ELSE delivery.split_deferral.waiting END
				""")
			.param("job", jobId)
			.param("product", productId)
			.param("waiting", waiting)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
