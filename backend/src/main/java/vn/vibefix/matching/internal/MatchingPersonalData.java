package vn.vibefix.matching.internal;

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
 * {@code matching}'s part in exports, erasure, and Product deletion (AD-24): a Mentor exports their
 * own Applications; erasing a Mentor or a Customer deletes the Applications they made or received,
 * and deleting a Product deletes those to its Jobs. A selected Application will block erasure once
 * selection exists (Story 3.7).
 */
@Component
class MatchingPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	MatchingPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("matching.application");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> applications = this.jdbc.sql("""
				SELECT job_id, status, price_vnd, timeline_days, approach, submitted_at, ended_at, end_reason
				FROM matching.application WHERE mentor_account_id = :id ORDER BY submitted_at, id
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> application = new LinkedHashMap<>();
			application.put("jobId", rs.getObject("job_id", UUID.class).toString());
			application.put("status", rs.getString("status"));
			application.put("priceVnd", rs.getLong("price_vnd"));
			application.put("timelineDays", rs.getInt("timeline_days"));
			application.put("approach", rs.getString("approach"));
			application.put("submittedAt", rs.getTimestamp("submitted_at").toInstant().toString());
			application.put("endedAt", rs.getTimestamp("ended_at") == null ? null
					: rs.getTimestamp("ended_at").toInstant().toString());
			application.put("endReason", rs.getString("end_reason"));
			return application;
		}).list();
		return applications.isEmpty() ? Optional.empty()
				: Optional.of(new PersonalDataSection("matching", Map.of("applications", applications)));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM matching.application WHERE mentor_account_id = :id OR customer_account_id = :id")
			.param("id", accountId)
			.update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		this.jdbc.sql("DELETE FROM matching.application WHERE product_id = :id").param("id", productId).update();
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
