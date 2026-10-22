package vn.vibefix.dispute.internal;

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
 * {@code dispute}'s part in exports, erasure, and Product deletion (AD-24): a person exports the Disputes
 * they opened and the Team Issues they raised; erasure clears them as the opener, the raiser, or the
 * Mentor, and a Team Issue not ended blocks it, since its hold would never end; deleting a Product deletes
 * its Disputes and Team Issues.
 */
@Component
class DisputePersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	DisputePersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("dispute.dispute", "dispute.decision", "dispute.execution", "dispute.team_issue");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> disputes = this.jdbc.sql("""
				SELECT job_id, reason, status, opened_at FROM dispute.dispute WHERE opener_account_id = :id ORDER BY opened_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("reason", rs.getString("reason"));
			row.put("status", rs.getString("status"));
			row.put("openedAt", rs.getTimestamp("opened_at").toInstant().toString());
			return row;
		}).list();
		List<Map<String, Object>> issues = this.jdbc.sql("""
				SELECT job_id, subject, reason, phase, raised_at FROM dispute.team_issue WHERE raiser_account_id = :id
				ORDER BY raised_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("subject", rs.getString("subject"));
			row.put("reason", rs.getString("reason"));
			row.put("phase", rs.getString("phase"));
			row.put("raisedAt", rs.getTimestamp("raised_at").toInstant().toString());
			return row;
		}).list();
		if (disputes.isEmpty() && issues.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		if (!disputes.isEmpty()) {
			data.put("disputesOpened", disputes);
		}
		if (!issues.isEmpty()) {
			data.put("teamIssuesRaised", issues);
		}
		return Optional.of(new PersonalDataSection("dispute", data));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("UPDATE dispute.dispute SET opener_account_id = NULL WHERE opener_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE dispute.decision SET admin_account_id = NULL WHERE admin_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE dispute.team_issue SET raiser_account_id = NULL WHERE raiser_account_id = :id")
			.param("id", accountId)
			.update();
		this.jdbc.sql("UPDATE dispute.team_issue SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		for (String table : List.of("dispute.execution", "dispute.decision")) {
			this.jdbc.sql("DELETE FROM %s WHERE dispute_id IN (SELECT id FROM dispute.dispute WHERE product_id = :id)"
				.formatted(table)).param("id", productId).update();
		}
		this.jdbc.sql("DELETE FROM dispute.dispute WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("DELETE FROM dispute.team_issue WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return this.jdbc.sql("""
				SELECT DISTINCT job_id FROM dispute.team_issue
				WHERE phase <> 'ENDED' AND (raiser_account_id = :id OR mentor_account_id = :id)
				""")
			.param("id", accountId)
			.query(UUID.class)
			.list()
			.stream()
			.map(job -> new ErasureBlocker("dispute", "TEAM_ISSUE_OPEN", job))
			.toList();
	}

}
