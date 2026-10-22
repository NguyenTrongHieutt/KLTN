package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.AgreementHistory;
import vn.vibefix.job.internal.Agreements.Content;
import vn.vibefix.job.internal.Agreements.Criterion;
import vn.vibefix.job.internal.Agreements.Milestone;

/** {@link AgreementHistory} over {@code job.confirmed_agreement}, each version's snapshot as it was confirmed. */
@Component
class ConfirmedAgreementHistory implements AgreementHistory {

	private final JdbcClient jdbc;

	ConfirmedAgreementHistory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ConfirmedVersion> confirmedVersions(UUID jobId) {
		return this.jdbc.sql("""
				SELECT version_no, approved_at, confirmed_at, content::text AS content FROM job.confirmed_agreement
				WHERE job_id = :job ORDER BY version_no
				""").param("job", jobId).query((rs, n) -> {
			Content content = AgreementReviews.content(rs.getString("content"));
			List<MilestoneLine> milestones = new ArrayList<>();
			for (Milestone milestone : content.milestones()) {
				List<CriterionLine> criteria = new ArrayList<>();
				for (Criterion criterion : milestone.criteria()) {
					criteria.add(new CriterionLine(criterion.id(), criteria.size() + 1, criterion.statement(),
							criterion.checkSteps()));
				}
				milestones.add(new MilestoneLine(milestone.id(), milestones.size() + 1, milestone.title(), milestone.priceVnd(),
						milestone.dueOn(), criteria));
			}
			return new ConfirmedVersion(rs.getInt("version_no"), rs.getTimestamp("approved_at").toInstant(),
					rs.getTimestamp("confirmed_at").toInstant(), content.scope(), content.priceVnd(), milestones);
		}).list();
	}

}
