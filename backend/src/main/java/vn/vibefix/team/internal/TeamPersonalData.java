package vn.vibefix.team.internal;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * {@code team}'s part in exports and erasure (AD-24): a person's Team, memberships, and requests.
 * Erasing a Student deletes their memberships and requests; erasing a Mentor deletes their Team with
 * its memberships and requests, so its Students are free to join another Team.
 */
@Component
class TeamPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	TeamPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("team.team", "team.membership", "team.team_request", "team.crew_reservation");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		Map<String, Object> data = new LinkedHashMap<>();
		this.jdbc.sql("SELECT status, created_at FROM team.team WHERE mentor_account_id = :id")
			.param("id", accountId)
			.query((rs, n) -> Map.of("status", rs.getString("status"), "createdAt",
					rs.getTimestamp("created_at").toInstant().toString()))
			.optional()
			.ifPresent(team -> data.put("team", team));
		List<Map<String, Object>> memberships = this.jdbc.sql("""
				SELECT joined_at, left_at, left_reason FROM team.membership WHERE student_account_id = :id ORDER BY joined_at
				""").param("id", accountId).query((rs, n) -> {
			Map<String, Object> membership = new LinkedHashMap<>();
			membership.put("joinedAt", rs.getTimestamp("joined_at").toInstant().toString());
			Timestamp left = rs.getTimestamp("left_at");
			if (left != null) {
				membership.put("leftAt", left.toInstant().toString());
				membership.put("leftReason", rs.getString("left_reason"));
			}
			return membership;
		}).list();
		if (!memberships.isEmpty()) {
			data.put("memberships", memberships);
		}
		// The person's own requests, sent or received; the other side is not named.
		List<Map<String, Object>> requests = this.jdbc.sql("""
				SELECT r.kind, r.status, r.created_at FROM team.team_request r JOIN team.team t ON t.id = r.team_id
				WHERE r.student_account_id = :id OR t.mentor_account_id = :id ORDER BY r.created_at
				""").param("id", accountId).query((rs, n) -> Map.<String, Object>of("kind", rs.getString("kind"), "status",
				rs.getString("status"), "createdAt", rs.getTimestamp("created_at").toInstant().toString())).list();
		if (!requests.isEmpty()) {
			data.put("teamRequests", requests);
		}
		return data.isEmpty() ? Optional.empty() : Optional.of(new PersonalDataSection("team", data));
	}

	@Override
	@Transactional
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM team.team_request WHERE student_account_id = :id OR team_id IN "
				+ "(SELECT id FROM team.team WHERE mentor_account_id = :id)").param("id", accountId).update();
		// A reservation outlives no membership; job refuses an erasure while a Crew row is unreleased.
		this.jdbc.sql("""
				DELETE FROM team.crew_reservation WHERE membership_id IN (SELECT id FROM team.membership
				    WHERE student_account_id = :id OR team_id IN (SELECT id FROM team.team WHERE mentor_account_id = :id))
				""").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM team.membership WHERE student_account_id = :id OR team_id IN "
				+ "(SELECT id FROM team.team WHERE mentor_account_id = :id)").param("id", accountId).update();
		this.jdbc.sql("DELETE FROM team.team WHERE mentor_account_id = :id").param("id", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
