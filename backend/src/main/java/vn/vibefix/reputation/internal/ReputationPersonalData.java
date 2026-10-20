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
 * the Ratings they gave, the published ones they received, and the private feedback they wrote; a
 * Mentor's export never holds feedback about them. Erasing a person deletes what they received and the
 * feedback they wrote or that is about them, and keeps their given Ratings without them. Deleting a
 * Product keeps the people's Ratings and clears the Job's title.
 */
@Component
class ReputationPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	ReputationPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("reputation.rating_window", "reputation.rating", "reputation.private_feedback");
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
		if (given.isEmpty() && received.isEmpty() && feedback.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> section = new LinkedHashMap<>();
		section.put("ratingsGiven", given);
		section.put("ratingsReceived", received);
		section.put("privateFeedbackGiven", feedback);
		return Optional.of(new PersonalDataSection("reputation", section));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM reputation.private_feedback WHERE student_account_id = :id OR mentor_account_id = :id")
			.param("id", accountId).update();
		this.jdbc.sql("DELETE FROM reputation.rating WHERE ratee_account_id = :id").param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.rating SET rater_account_id = NULL WHERE rater_account_id = :id")
			.param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.rating_window SET customer_account_id = NULL WHERE customer_account_id = :id")
			.param("id", accountId).update();
		this.jdbc.sql("UPDATE reputation.rating_window SET mentor_account_id = NULL WHERE mentor_account_id = :id")
			.param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("UPDATE reputation.rating_window SET job_title = '' WHERE product_id = :id").param("id", productId).update();
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
