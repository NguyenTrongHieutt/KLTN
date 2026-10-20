package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.internal.AgreementReviews.Confirmed;
import vn.vibefix.job.internal.Agreements.Criterion;
import vn.vibefix.job.internal.Agreements.Milestone;

/**
 * {@link AgreementCriteria} over the latest confirmed Agreement version (FR-22, AD-29): its snapshot
 * holds every Milestone and criterion with the stable IDs the live draft gave them.
 */
@Component
class ConfirmedCriteria implements AgreementCriteria {

	private final JdbcClient jdbc;

	private final AgreementReviews reviews;

	ConfirmedCriteria(JdbcClient jdbc, AgreementReviews reviews) {
		this.jdbc = jdbc;
		this.reviews = reviews;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CriteriaInForce> inForce(UUID jobId) {
		Optional<Confirmed> confirmed = this.reviews.confirmed(jobId);
		if (confirmed.isEmpty()) {
			return Optional.empty();
		}
		UUID product = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(UUID.class)
			.single();
		List<MilestoneInForce> inForce = new ArrayList<>();
		List<CriterionInForce> criteria = new ArrayList<>();
		List<Milestone> milestones = confirmed.get().content().milestones();
		for (int m = 0; m < milestones.size(); m++) {
			Milestone milestone = milestones.get(m);
			inForce.add(new MilestoneInForce(milestone.id(), m + 1, milestone.title(), milestone.priceVnd(), milestone.dueOn()));
			for (int c = 0; c < milestone.criteria().size(); c++) {
				Criterion criterion = milestone.criteria().get(c);
				criteria.add(new CriterionInForce(criterion.id(), milestone.id(), m + 1, milestone.title(), c + 1,
						criterion.statement(), criterion.checkSteps()));
			}
		}
		return Optional.of(new CriteriaInForce(jobId, product, confirmed.get().versionNo(), inForce, criteria));
	}

}
