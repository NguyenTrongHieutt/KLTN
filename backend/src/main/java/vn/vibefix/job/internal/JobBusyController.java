package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.Busy;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * The busy marker as JOB-H's banner reads it (UX-DR21, AD-28), polled while it is set. The Job's
 * Customer, its Mentor, and its Crew members whose rows are not released read it; anyone else finds
 * nothing.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobBusyController {

	/**
	 * The marker, or nothing set.
	 * @param purpose why a precondition operation holds the Job, as {@link JobBusy.Purpose}
	 * @param milestone the number of the Milestone it acts on, for an acceptance
	 * @param handoverId the handover an acceptance merges, whose report states the outcome once it clears
	 * @param audience {@code CUSTOMER}, {@code MENTOR}, or {@code CREW}: the Final Merge reads differently to each
	 */
	record BusyView(boolean busy, JobBusy.@Nullable Purpose purpose, @Nullable Instant since, @Nullable Integer milestone,
			@Nullable UUID handoverId, String audience) {
	}

	private final JobBusy busy;

	private final ExternalAccessGrants grants;

	private final M1Fund numbers;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	private final JobRoleReader roles;

	JobBusyController(JobBusy busy, ExternalAccessGrants grants, M1Fund numbers, JdbcClient jdbc, CurrentCaller callers,
			JobRoleReader roles) {
		this.roles = roles;
		this.busy = busy;
		this.grants = grants;
		this.numbers = numbers;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/busy")
	BusyView view(@PathVariable UUID jobId) {
		Job job = this.grants.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		String audience = reader(job, this.callers.get());
		Busy marker = this.busy.job(jobId).flatMap(JobBusy.BusyJob::busy).orElse(null);
		if (marker == null) {
			return new BusyView(false, null, null, null, null, audience);
		}
		String subject = marker.subjectToken();
		UUID handover = marker.purpose() == JobBusy.Purpose.ACCEPTANCE && subject != null ? UUID.fromString(subject) : null;
		return new BusyView(true, marker.purpose(), marker.since(), milestone(jobId, marker), handover, audience);
	}

	/** The Milestone whose current handover the marker names. */
	private @Nullable Integer milestone(UUID jobId, Busy marker) {
		if (marker.purpose() != JobBusy.Purpose.ACCEPTANCE || marker.subjectToken() == null) {
			return null;
		}
		UUID milestoneId = this.jdbc.sql("SELECT id FROM job.milestone WHERE job_id = :job AND handover_id = :handover")
			.param("job", jobId)
			.param("handover", UUID.fromString(marker.subjectToken()))
			.query(UUID.class)
			.optional()
			.orElse(null);
		return milestoneId == null ? null : this.numbers.number(jobId, milestoneId);
	}

	/** The Job's Customer, its Mentor, or one of its unreleased Crew, by role in the Job; anyone else finds nothing. */
	private String reader(Job job, Caller caller) {
		JobRole role = this.roles.roleOf(job, caller);
		if (role.isCustomer()) {
			return "CUSTOMER";
		}
		if (role.isMentor()) {
			return "MENTOR";
		}
		if (role.isCrew() && this.grants.onCrew(job.id(), caller.requireAccountId())) {
			return "CREW";
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

}
