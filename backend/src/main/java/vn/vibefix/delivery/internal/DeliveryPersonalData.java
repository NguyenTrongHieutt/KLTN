package vn.vibefix.delivery.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code delivery}'s part in exports, erasure, and Product deletion (AD-24): a Mentor exports the
 * handovers they made with their marks and notes, and a Customer the checks they decided with; erasing
 * an account clears it as the handover's Mentor or decider (the handover stays, without a person);
 * deleting a Product deletes its handovers. A running Job blocks erasure through {@code job}.
 */
@Component
class DeliveryPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	DeliveryPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("delivery.handover", "delivery.handover_criterion", "delivery.handover_item_note",
				"delivery.handover_check", "delivery.follow_up");
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
		this.jdbc.sql("UPDATE delivery.handover SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE delivery.handover SET decided_by = NULL WHERE decided_by = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE delivery.follow_up SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM delivery.follow_up WHERE product_id = :id").param("id", productId).update();
		String handovers = "SELECT id FROM delivery.handover WHERE product_id = :id";
		this.jdbc.sql("DELETE FROM delivery.handover_criterion WHERE handover_id IN (" + handovers + ")")
			.param("id", productId)
			.update();
		this.jdbc.sql("DELETE FROM delivery.handover_item_note WHERE handover_id IN (" + handovers + ")")
			.param("id", productId)
			.update();
		this.jdbc.sql("DELETE FROM delivery.handover_check WHERE handover_id IN (" + handovers + ")")
			.param("id", productId)
			.update();
		this.jdbc.sql("DELETE FROM delivery.handover WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
