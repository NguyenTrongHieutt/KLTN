package vn.vibefix.reputation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.reputation.CrewRatings;
import vn.vibefix.reputation.RatingRemoved;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link CrewRatings} over the published, unhidden {@code MENTOR_TO_STUDENT} Ratings; a removal hides the
 * Rating as an Administrator's moderation does (FR-60), so every summary already leaves it out.
 */
@Component
class VisibleCrewRatings implements CrewRatings {

	private static final String VISIBLE = """
			SELECT r.id, r.job_id, w.job_title, r.rater_account_id, r.ratee_account_id, r.stars, w.crew_published_at
			FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
			WHERE r.kind = 'MENTOR_TO_STUDENT' AND r.hidden_at IS NULL AND w.crew_published_at IS NOT NULL
			""";

	private final JdbcClient jdbc;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	private final AuditLog auditLog;

	VisibleCrewRatings(JdbcClient jdbc, ApplicationEventPublisher events, IdGenerator ids, Clock clock, AuditLog auditLog) {
		this.auditLog = auditLog;
		this.jdbc = jdbc;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CrewRating> receivedBy(UUID studentId) {
		return this.jdbc.sql(VISIBLE + " AND r.ratee_account_id = :id ORDER BY w.crew_published_at DESC, r.id")
			.param("id", studentId)
			.query(VisibleCrewRatings::rating)
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CrewRating> find(UUID ratingId) {
		return this.jdbc.sql(VISIBLE + " AND r.id = :id").param("id", ratingId).query(VisibleCrewRatings::rating).optional();
	}

	@Override
	@Transactional
	public boolean removeRating(UUID ratingId, String reason) {
		int hidden = this.jdbc.sql("""
				UPDATE reputation.rating SET hidden_at = :now, hidden_reason = :reason WHERE id = :id AND hidden_at IS NULL
				""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("reason", reason.strip())
			.param("id", ratingId)
			.update();
		if (hidden == 0) {
			return false;
		}
		this.events.publishEvent(new RatingRemoved(this.ids.newId(), ratingId));
		return true;
	}

	@Override
	@Transactional
	public Optional<RatingEvidence> evidence(UUID ratingId, UUID adminId) {
		Optional<RatingEvidence> found = this.jdbc.sql("""
				SELECT id, job_id, stars, comment, created_at, hidden_at FROM reputation.rating
				WHERE id = :id AND kind = 'MENTOR_TO_STUDENT'
				""").param("id", ratingId).query((rs, n) -> {
			Timestamp hidden = rs.getTimestamp("hidden_at");
			return new RatingEvidence(rs.getObject("id", UUID.class), rs.getInt("stars"), rs.getString("comment"),
					rs.getTimestamp("created_at").toInstant(), hidden == null ? null : hidden.toInstant());
		}).optional();
		found.ifPresent(rating -> this.auditLog
			.record(AuditEntry.of(AuditActor.user(adminId), "reputation.admin_read", AuditObject.of("reputation.rating", ratingId))));
		return found;
	}

	private static CrewRating rating(ResultSet rs, int n) throws SQLException {
		return new CrewRating(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), rs.getString("job_title"),
				rs.getObject("rater_account_id", UUID.class), rs.getObject("ratee_account_id", UUID.class), rs.getInt("stars"),
				rs.getTimestamp("crew_published_at").toInstant());
	}

}
