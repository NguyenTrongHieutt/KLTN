package vn.vibefix.reputation.internal;

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
 * {@code reputation}'s part in exports, erasure, and Product deletion (AD-24, FR-78): a person exports
 * the Ratings they gave, the published ones they received, the private feedback they wrote, their
 * Portfolio Entries, and the summaries they drafted or decided; a Mentor's export never holds feedback
 * about them. Erasing a person deletes what they received, the feedback they wrote or that is about
 * them, and their entries, and keeps their given Ratings and summaries without them. Deleting a Product
 * keeps the people's Ratings and entries, clears the Job's title and the Product name, and deletes the
 * summary.
 */
@Component
class ReputationPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	ReputationPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("reputation.rating_window", "reputation.rating", "reputation.private_feedback",
				"reputation.portfolio_entry", "reputation.portfolio_summary");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> given = this.jdbc.sql("""
				SELECT job_id, kind, stars, comment, created_at FROM reputation.rating WHERE rater_account_id = :id
				ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> rating(rs.getObject("job_id", UUID.class), rs.getString("kind"),
				rs.getInt("stars"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant().toString())).list();
		List<Map<String, Object>> received = this.jdbc.sql("""
				SELECT r.job_id, r.kind, r.stars, r.comment, r.created_at
				FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
				WHERE r.ratee_account_id = :id
				  AND ((r.kind <> 'MENTOR_TO_STUDENT' AND w.pair_published_at IS NOT NULL)
				    OR (r.kind = 'MENTOR_TO_STUDENT' AND w.crew_published_at IS NOT NULL))
				ORDER BY r.created_at, r.id
				""").param("id", accountId).query((rs, n) -> rating(rs.getObject("job_id", UUID.class), rs.getString("kind"),
				rs.getInt("stars"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant().toString())).list();
		List<Map<String, Object>> feedback = this.jdbc.sql("""
				SELECT job_id, stars, comment, created_at FROM reputation.private_feedback WHERE student_account_id = :id
				ORDER BY created_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("stars", rs.getInt("stars"));
			row.put("comment", rs.getString("comment"));
			row.put("at", rs.getTimestamp("created_at").toInstant().toString());
			return row;
		}).list();
		List<Map<String, Object>> entries = this.jdbc.sql("""
				SELECT job_id, role, job_title, job_types, milestones_accepted, work_items_approved, milestones_handed_over,
				       reviews_written, closed_at, published_at
				FROM reputation.portfolio_entry WHERE account_id = :id ORDER BY closed_at, job_id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("role", rs.getString("role"));
			row.put("title", rs.getString("job_title"));
			row.put("jobTypes", List.of((Object[]) rs.getArray("job_types").getArray()));
			row.put("milestones", rs.getInt("milestones_accepted"));
			row.put("workItemsApproved", rs.getObject("work_items_approved"));
			row.put("milestonesHandedOver", rs.getObject("milestones_handed_over"));
			row.put("reviewsWritten", rs.getObject("reviews_written"));
			row.put("closedAt", rs.getTimestamp("closed_at").toInstant().toString());
			row.put("published", rs.getTimestamp("published_at") != null);
			return row;
		}).list();
		List<Map<String, Object>> summaries = this.jdbc.sql("""
				SELECT job_id, status, text FROM reputation.portfolio_summary
				WHERE drafted_by = :id OR decided_by = :id ORDER BY created_at, job_id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("jobId", rs.getObject("job_id", UUID.class).toString());
			row.put("status", rs.getString("status"));
			row.put("text", rs.getString("text"));
			return row;
		}).list();
		if (given.isEmpty() && received.isEmpty() && feedback.isEmpty() && entries.isEmpty() && summaries.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> section = new LinkedHashMap<>();
		section.put("ratingsGiven", given);
		section.put("ratingsReceived", received);
		section.put("privateFeedbackGiven", feedback);
		section.put("portfolioEntries", entries);
		section.put("portfolioSummaries", summaries);
		return Optional.of(new PersonalDataSection("reputation", section));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM reputation.private_feedback WHERE student_account_id = :id OR mentor_account_id = :id")
			.param("id", accountId).update();
		this.jdbc.sql("DELETE FROM reputation.rating WHERE ratee_account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.rating SET rater_account_id = NULL WHERE rater_account_id = :id")
			.param("id", accountId).update();
		this.jdbc.sql("DELETE FROM reputation.portfolio_entry WHERE account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.portfolio_summary SET drafted_by = NULL WHERE drafted_by = :id")
			.param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.portfolio_summary SET decided_by = NULL WHERE decided_by = :id")
			.param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.rating_window SET customer_account_id = NULL WHERE customer_account_id = :id")
			.param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.rating_window SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("UPDATE reputation.rating_window SET job_title = '' WHERE product_id = :id").param("id", productId).update();
		this.jdbc.sql("UPDATE reputation.portfolio_entry SET job_title = '', product_name = '' WHERE product_id = :id")
			.param("id", productId).update();
		this.jdbc.sql("DELETE FROM reputation.portfolio_summary WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

	private static Map<String, Object> rating(UUID jobId, String kind, int stars, String comment, String at) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("jobId", jobId.toString());
		row.put("kind", kind);
		row.put("stars", stars);
		row.put("comment", comment);
		row.put("at", at);
		return row;
	}

}
