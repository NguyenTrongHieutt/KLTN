package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.internal.JobLifecycle.Applied;
import vn.vibefix.job.internal.JobLifecycle.Planned;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link MilestoneFunding} (FR-41, AD-4): the Milestones of a Job whose Agreement is confirmed, and M1
 * with its cascade. Milestone 1 of a Job in Agreement funds with J6 first and, for an Assessment Job,
 * J7 after; a later Milestone funds with M1 alone.
 */
@Component
class Fundings implements MilestoneFunding {

	private static final UUID DRY_RUN = new UUID(0, 0);

	private final JdbcClient jdbc;

	private final JobLifecycle lifecycle;

	Fundings(JdbcClient jdbc, JobLifecycle lifecycle) {
		this.jdbc = jdbc;
		this.lifecycle = lifecycle;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FundingJob> forCustomer(UUID customerId, UUID jobId) {
		return byId(jobId).filter(job -> job.customerId().equals(customerId));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<FundingJob> byId(UUID jobId) {
		return job(jobId).filter(job -> job.agreementVersionNo() != null)
			.map(job -> new FundingJob(job.id(), job.productId(), job.customerId(), job.title(), job.state().name(), job.version(),
					job.frozenAt() != null, job.agreementVersionNo(), milestones(job.id())));
	}

	@Override
	@Transactional(readOnly = true)
	public @Nullable ApiException refusal(UUID jobId, UUID milestoneId) {
		Job job = job(jobId).orElse(null);
		if (job == null) {
			return new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.lifecycle.refusal(jobId, Trigger.SYSTEM, cascade(job, milestoneId, DRY_RUN));
	}

	@Override
	@Transactional
	public Optional<Funded> tryFund(UUID jobId, UUID milestoneId, UUID paymentOrderId) {
		Job job = job(jobId).orElse(null);
		if (job == null) {
			return Optional.empty();
		}
		Optional<List<Applied>> applied = this.lifecycle.tryApplyCascade(jobId, Trigger.SYSTEM,
				cascade(job, milestoneId, paymentOrderId));
		if (applied.isEmpty()) {
			return Optional.empty();
		}
		UUID transition = applied.get()
			.stream()
			.filter(step -> "FUNDED".equals(step.to()))
			.map(Applied::transitionId)
			.findFirst()
			.orElseThrow();
		return milestones(jobId).stream()
			.filter(milestone -> milestone.milestoneId().equals(milestoneId))
			.findFirst()
			.map(milestone -> new Funded(transition, milestone.number(), pin(milestoneId)));
	}

	/** AD-4: Milestone 1 of a Job in Agreement funds with J6 first, and an Assessment Job with J7 after. */
	private static List<Planned> cascade(Job job, UUID milestoneId, UUID paymentOrderId) {
		List<Planned> cascade = new ArrayList<>();
		boolean starts = job.state() == JobState.AGREEMENT;
		if (starts) {
			cascade.add(Planned.job(Transition.J6));
		}
		cascade.add(Planned.milestone(Transition.M1, milestoneId, Map.of("paymentOrderId", paymentOrderId)));
		if (starts && !job.code()) {
			cascade.add(Planned.job(Transition.J7));
		}
		return cascade;
	}

	private Optional<Job> job(UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query(Jobs::job)
			.optional();
	}

	private List<FundingMilestone> milestones(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, title, coalesce(price_vnd, 0) AS price, state,
				       row_number() OVER (ORDER BY position, created_at, id) AS n
				FROM job.milestone WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id
				""")
			.param("job", jobId)
			.query((rs, n) -> new FundingMilestone(rs.getObject("id", UUID.class), rs.getInt("n"), rs.getString("title"),
					rs.getLong("price"), rs.getString("state")))
			.list();
	}

	private int pin(UUID milestoneId) {
		return this.jdbc.sql("SELECT crew_terms_version_no FROM job.milestone WHERE id = :id")
			.param("id", milestoneId)
			.query(Integer.class)
			.single();
	}

}
