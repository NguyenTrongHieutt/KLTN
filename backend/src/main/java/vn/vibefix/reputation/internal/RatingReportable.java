package vn.vibefix.reputation.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ReportableContent;

/**
 * FR-69: a Rating is reportable by any signed-in user once it is published and not hidden, as the public
 * profiles and MEN-3 show it, and by its rater from the moment they wrote it.
 */
@Component
class RatingReportable implements ReportableContent {

	private final JdbcClient jdbc;

	RatingReportable(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> kinds() {
		return Set.of("RATING");
	}

	@Override
	public Optional<Reported> reportable(String kind, UUID id, Caller caller) {
		if (!"RATING".equals(kind) || caller.isAnonymous() || caller.isAdministrator()) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				SELECT r.job_id, w.product_id, r.rater_account_id, r.stars || ' sao · ' || w.job_title AS label
				FROM reputation.rating r JOIN reputation.rating_window w ON w.job_id = r.job_id
				WHERE r.id = :id AND r.hidden_at IS NULL
				  AND (r.rater_account_id = :caller
				    OR (r.kind <> 'MENTOR_TO_STUDENT' AND w.pair_published_at IS NOT NULL)
				    OR (r.kind = 'MENTOR_TO_STUDENT' AND w.crew_published_at IS NOT NULL))
				""")
			.param("id", id)
			.param("caller", caller.requireAccountId())
			.query((rs, n) -> new Reported(rs.getObject("job_id", UUID.class), rs.getObject("product_id", UUID.class),
					rs.getObject("rater_account_id", UUID.class), rs.getString("label")))
			.optional();
	}

}
