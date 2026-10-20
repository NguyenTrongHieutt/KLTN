package vn.vibefix.work.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.CriterionInForce;
import vn.vibefix.work.CriteriaDone;

/**
 * {@link CriteriaDone} over the seeded Stories: a criterion counts when its Story is in "Xong" and the
 * latest history entry that moved it there is an approval (AD-33, AD-39).
 */
@Component
class WorkCriteriaDone implements CriteriaDone {

	private final JdbcClient jdbc;

	private final AgreementCriteria criteria;

	WorkCriteriaDone(JdbcClient jdbc, AgreementCriteria criteria) {
		this.jdbc = jdbc;
		this.criteria = criteria;
	}

	@Override
	@Transactional(readOnly = true)
	public Progress criteriaDone(UUID jobId, UUID milestoneId) {
		Set<UUID> inForce = new HashSet<>();
		this.criteria.inForce(jobId).ifPresent(found -> found.criteria()
			.stream()
			.filter(criterion -> criterion.milestoneId().equals(milestoneId))
			.map(CriterionInForce::criterionId)
			.forEach(inForce::add));
		if (inForce.isEmpty()) {
			return new Progress(0, 0);
		}
		Set<UUID> approved = new HashSet<>(this.jdbc.sql("""
				SELECT w.criterion_id FROM work.work_item w
				WHERE w.job_id = :job AND w.milestone_id = :milestone AND w.criterion_id IS NOT NULL AND w.status = 'DONE'
				  AND (SELECT h.action FROM work.work_item_history h
				       WHERE h.work_item_id = w.id AND h.changes -> 'status' ->> 'to' = 'DONE'
				       ORDER BY h.seq DESC LIMIT 1) = 'APPROVED'
				""").param("job", jobId).param("milestone", milestoneId).query(UUID.class).list());
		approved.retainAll(inForce);
		return new Progress(approved.size(), inForce.size());
	}

}
