package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.internal.JobLifecycle.Applying;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * M1 (FR-41): the verified payment of a Milestone's Payment Order makes it Funded. Its guards: no
 * other Held Milestone, the Job in Agreement (Milestone 1, after FR-17) or Active, and the Milestone
 * the first Planned one. It records when, and the Crew Terms version in force as the pin its release
 * uses (FR-42, AD-41); the Mentor and the active Crew are told. It only runs in a cascade, after J6 for
 * Milestone 1.
 */
@Component
class M1Fund implements JobLifecycle.Step {

	static final String FUNDED = "job.milestone_funded";

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Notifications notifications;

	M1Fund(JdbcClient jdbc, CrewService crews, Notifications notifications) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.notifications = notifications;
	}

	@Override
	public Transition transition() {
		return Transition.M1;
	}

	/** @throws ApiException {@code GUARD_FAILED MILESTONE_HELD} or {@code MILESTONE_NOT_FUNDABLE} */
	@Override
	public void check(Applying applying) {
		Job job = applying.job();
		UUID milestone = applying.milestoneId();
		Boolean held = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM job.milestone WHERE job_id = :job AND id <> :id AND removed_at IS NULL
				    AND state IN ('FUNDED', 'REVISION_REQUESTED', 'SUBMITTED'))
				""").param("job", job.id()).param("id", milestone).query(Boolean.class).single();
		if (held) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MILESTONE_HELD);
		}
		UUID next = this.jdbc.sql("""
				SELECT id FROM job.milestone WHERE job_id = :job AND removed_at IS NULL AND state = 'PLANNED'
				ORDER BY position, created_at, id LIMIT 1
				""").param("job", job.id()).query(UUID.class).optional().orElse(null);
		boolean first = milestone.equals(firstMilestone(job.id()));
		boolean agreement = job.state() == JobState.AGREEMENT && job.agreementVersionNo() != null && first;
		if (!milestone.equals(next) || !(agreement || job.state() == JobState.ACTIVE)) {
			throw new ApiException(ErrorCode.GUARD_FAILED, JobReason.MILESTONE_NOT_FUNDABLE);
		}
	}

	@Override
	public void apply(Applying applying) {
		UUID job = applying.job().id();
		int pin = this.crews.inForce(job)
			.orElseThrow(() -> new IllegalStateException("M1 on " + job + " found no Crew Terms version in force"));
		applying.set("funded_at", applying.now());
		applying.set("crew_terms_version_no", pin);
		applying.fact("milestoneId", applying.milestoneId().toString());
		applying.fact("crewTermsVersionNo", String.valueOf(pin));
		applying.fact("paymentOrderId", String.valueOf(applying.input("paymentOrderId")));
		applying.fact("number", String.valueOf(number(job, applying.milestoneId())));
	}

	@Override
	public void afterWrite(Applying applying) {
		Job job = applying.job();
		List<Recipient> team = new ArrayList<>(List.of(new Recipient(Objects.requireNonNull(job.mentorId()), Audience.MENTOR)));
		this.crews.rows(job.id())
			.stream()
			.filter(row -> "ACTIVE".equals(row.status()))
			.forEach(row -> team.add(new Recipient(row.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(FUNDED, applying.transitionId(), team,
				Map.of("job", job.title(), "n", String.valueOf(number(job.id(), applying.milestoneId()))), "/cong-viec/" + job.id(),
				job.id(), null));
	}

	private UUID firstMilestone(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id FROM job.milestone WHERE job_id = :job AND removed_at IS NULL ORDER BY position, created_at, id LIMIT 1
				""").param("job", jobId).query(UUID.class).optional().orElse(null);
	}

	/** The Milestone's number from 1, in the Agreement's order. */
	int number(UUID jobId, UUID milestoneId) {
		return this.jdbc.sql("""
				SELECT n FROM (SELECT id, row_number() OVER (ORDER BY position, created_at, id) AS n FROM job.milestone
				    WHERE job_id = :job AND removed_at IS NULL) numbered WHERE id = :id
				""").param("job", jobId).param("id", milestoneId).query(Integer.class).single();
	}

}
