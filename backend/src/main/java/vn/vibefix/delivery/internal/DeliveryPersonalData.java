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
 * handovers they made with their marks, notes, links, and preview link or no-preview reason, and a Customer the checks
 * they decided with; erasing
 * an account clears it as the handover's Mentor or decider (the handover stays, without a person);
 * deleting a Product deletes its handovers. A Mentor's screenshot uploads are theirs (Story 8.3): erasure
 * deletes those no handover carries and clears them as uploader of the others; deleting a Product deletes
 * them all, files included. A handover submission is working state while the runner copies it (Story 23.8a): its
 * draft names the Mentor and the Customer, so an erasure and a Product deletion both delete the row whole, and a
 * retirement does too. A running Job blocks erasure through {@code job}.
 * <p>
 * While a Milestone Split of a Job is not final, its handovers stay whole as the evidence a Split Resolution reads
 * (AD-24, A-01, Story 24.3b): what each said (criteria, checks, links, images, Sensitive Changes, acceptances, the
 * preview link and the no-preview reason) and the Handover Commit authors, with the handover rows they hang from. The
 * Mentor's uploads no handover took, the follow-ups and the submissions go at once; a row in
 * {@code delivery.split_deferral} says what waits, and the listener on {@link MilestoneSplitFinalized} finishes it once
 * the Job has no open split.
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
		return Set.of("delivery.handover", "delivery.handover_criterion",
				"delivery.handover_check", "delivery.follow_up", "delivery.handover_commit", "delivery.handover_sensitive_change",
				"delivery.handover_screenshot", "delivery.handover_link", "delivery.handover_acceptance",
				"delivery.handover_submission");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> handovers = this.jdbc.sql("""
				SELECT id, job_id, milestone_number, round, created_at, preview_url, no_preview_reason FROM delivery.handover
				WHERE mentor_account_id = :id ORDER BY seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> handover = new LinkedHashMap<>();
			UUID id = rs.getObject("id", UUID.class);
			handover.put("jobId", rs.getObject("job_id", UUID.class).toString());
			handover.put("milestone", rs.getInt("milestone_number"));
			handover.put("round", rs.getInt("round"));
			handover.put("at", rs.getTimestamp("created_at").toInstant().toString());
			// What the Mentor wrote to let the Customer try the change: the preview link, or why there is none.
			if (rs.getString("preview_url") != null) {
				handover.put("previewUrl", rs.getString("preview_url"));
			}
			if (rs.getString("no_preview_reason") != null) {
				handover.put("noPreviewReason", rs.getString("no_preview_reason"));
			}
			handover.put("criteria", this.jdbc.sql("""
					SELECT number, met, note FROM delivery.handover_criterion WHERE handover_id = :id ORDER BY number
					""").param("id", id).query((row, m) -> {
				Map<String, Object> criterion = new LinkedHashMap<>();
				criterion.put("number", row.getInt("number"));
				criterion.put("met", row.getBoolean("met"));
				criterion.put("note", row.getString("note"));
				return criterion;
			}).list());
			// Story 24.3: the links the Mentor put on the handover, as they wrote them.
			handover.put("links", this.jdbc.sql("""
					SELECT kind, label, url FROM delivery.handover_link WHERE handover_id = :id ORDER BY position
					""").param("id", id).query((row, m) -> {
				Map<String, Object> link = new LinkedHashMap<>();
				link.put("kind", row.getString("kind"));
				link.put("label", row.getString("label"));
				link.put("url", row.getString("url"));
				return link;
			}).list());
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
		// N-33 (FR-25, R28, Story 23.8b): the Mentor's duty confirmations, each with the version of the text they read.
		List<Map<String, Object>> teamCode = this.jdbc.sql("""
				SELECT job_id, milestone_number, team_code_notice_version, team_code_confirmed_at FROM delivery.handover
				WHERE team_code_account_id = :id ORDER BY seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("milestone", rs.getInt("milestone_number"));
			row.put("noticeVersion", rs.getString("team_code_notice_version"));
			row.put("at", rs.getTimestamp("team_code_confirmed_at").toInstant().toString());
			return row;
		}).list();
		// FR-86 (Story 25.5): the handovers the Customer of a Self-mentored Job returned, each with the reason they wrote.
		List<Map<String, Object>> returns = this.jdbc.sql("""
				SELECT job_id, milestone_number, voided_at, return_reason FROM delivery.handover
				WHERE returned_by_account_id = :id ORDER BY seq
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("milestone", rs.getInt("milestone_number"));
			row.put("at", rs.getTimestamp("voided_at").toInstant().toString());
			row.put("reason", rs.getString("return_reason"));
			return row;
		}).list();
		if (handovers.isEmpty() && decisions.isEmpty() && teamCode.isEmpty() && returns.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new PersonalDataSection("delivery", Map.of("handovers", handovers, "decisions", decisions,
				"teamCodeConfirmations", teamCode, "returns", returns)));
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
		// N-33 (Story 23.8b): the Mentor's duty confirmation stays, without the person who ticked it.
		this.jdbc.sql("UPDATE delivery.handover SET team_code_account_id = NULL WHERE team_code_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.handover SET decided_by = NULL WHERE decided_by = :id").param("id", accountId).update();
		// FR-86 (Story 25.5): a returned handover keeps its reason, as a decided one keeps its comments, without the person.
		this.jdbc.sql("UPDATE delivery.handover SET returned_by_account_id = NULL WHERE returned_by_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.handover_acceptance SET customer_account_id = NULL WHERE customer_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.follow_up SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId)
			.update();
		// A submission is working state (Story 23.8a): its draft names the Mentor and the Customer, so it goes whole.
		this.jdbc.sql("""
				DELETE FROM delivery.handover_submission WHERE mentor_account_id = :id OR customer_account_id = :id
				""").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		deleteOrDefer(productId, "DELETE");
	}

	/**
	 * NFR-10: what each handover said goes (criteria and notes, the Customer's marks and comments,
	 * screenshots, links, the preview link and the no-preview reason, recorded commits, Sensitive Changes, pending
	 * acceptances, follow-ups); each
	 * handover row and its status stay for the Milestone history. A Job with an open split keeps its handovers whole
	 * until the split is final.
	 */
	@Override
	public void retireJobData(UUID productId) {
		deleteOrDefer(productId, "RETIRE");
	}

	/**
	 * The follow-ups, the submissions and the Mentor's uploads no handover took go for every Job of the Product. What each
	 * handover said, its recorded commits and, on a deletion, the handover rows go too, unless an open split makes them
	 * wait.
	 */
	private void deleteOrDefer(UUID productId, String waiting) {
		Set<UUID> open = this.splits.openSplitJobs(productId);
		open.forEach(job -> defer(job, productId, waiting));
		this.jdbc.sql("DELETE FROM delivery.follow_up WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM delivery.handover_submission WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("SELECT file_id FROM delivery.handover_screenshot WHERE product_id = :id AND handover_id IS NULL")
			.param("id", productId)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
		this.jdbc.sql("DELETE FROM delivery.handover_screenshot WHERE product_id = :id AND handover_id IS NULL")
			.param("id", productId)
			.update();
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

	/**
	 * NFR-10 for one Job: what its handovers said (criteria and notes, the Customer's marks and comments, images and their
	 * files, links, the preview link and the no-preview reason, Sensitive Changes, pending acceptances), their recorded
	 * commits and, on a deletion, the handover rows; a retirement leaves each row and its status for the Milestone history.
	 */
	private void finish(UUID jobId, String waiting) {
		this.jdbc.sql("SELECT file_id FROM delivery.handover_screenshot WHERE job_id = :job")
			.param("job", jobId)
			.query(UUID.class)
			.list()
			.forEach(this.files::delete);
		this.jdbc.sql("DELETE FROM delivery.handover_screenshot WHERE job_id = :job").param("job", jobId).update();
		String handovers = "SELECT id FROM delivery.handover WHERE job_id = :job";
		for (String table : List.of("delivery.handover_criterion", "delivery.handover_check", "delivery.handover_link",
				"delivery.handover_sensitive_change", "delivery.handover_acceptance", "delivery.handover_commit")) {
			this.jdbc.sql("DELETE FROM " + table + " WHERE handover_id IN (" + handovers + ")").param("job", jobId).update();
		}
		// The preview link and the no-preview reason are what the handover said too (Story 24.3), like its links.
		this.jdbc.sql("""
				UPDATE delivery.handover SET preview_url = NULL, preview_checked_at = NULL, no_preview_reason = NULL
				WHERE job_id = :job AND (preview_url IS NOT NULL OR no_preview_reason IS NOT NULL)
				""").param("job", jobId).update();
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
