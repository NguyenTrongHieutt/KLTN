package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.identity.Notices;
import vn.vibefix.job.JobSelection;
import vn.vibefix.job.internal.JobController.DecisionRequest;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.product.ProductDirectory.ProductInfo;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * The Mentor's Jobs (FR-15, AD-19 Job commands): MEN-4 lists the Jobs whose Mentor they are, JOB-1
 * shows one with what the NDA panel needs, and "Từ chối" applies J5 while Selecting. Only the Job's
 * Mentor finds it; a revoked Mentor keeps their running Jobs.
 */
@RestController
@RequestMapping("/api/v1/mentor/jobs")
@Access(Gate.SIGNED_IN)
class MentorJobController {

	record MentorJobSummaryView(UUID id, String title, String productName, JobState state, Instant selectedAt) {
	}

	/**
	 * One Job for its Mentor.
	 * @param codeJob a Code Job, whose NDA panel shows N-23
	 * @param commitEmailNoticeAcknowledged N-23 acknowledged already, at its current version
	 */
	record MentorJobView(UUID id, String title, String productName, JobState state, long version,
			List<JobType> jobTypes, @Nullable String problem, @Nullable String expected, @Nullable String appUsers,
			@Nullable String doneCheck, Instant selectedAt, @Nullable Instant ndaDueAt, boolean codeJob,
			boolean commitEmailNoticeAcknowledged) {
	}

	private final Jobs jobs;

	private final JobLifecycle lifecycle;

	private final ProductDirectory products;

	private final Notices notices;

	private final MentorGate gate;

	private final JdbcClient jdbc;

	private final CurrentCaller callers;

	MentorJobController(Jobs jobs, JobLifecycle lifecycle, ProductDirectory products, Notices notices,
			MentorGate gate, JdbcClient jdbc, CurrentCaller callers) {
		this.jobs = jobs;
		this.lifecycle = lifecycle;
		this.products = products;
		this.notices = notices;
		this.gate = gate;
		this.jdbc = jdbc;
		this.callers = callers;
	}

	/** MEN-4 "Công việc của bạn": the Mentor's Jobs, most recently selected first. */
	@GetMapping
	List<MentorJobSummaryView> jobs() {
		UUID mentor = mentor();
		Map<UUID, String> names = new HashMap<>();
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS
				+ " FROM job.job WHERE mentor_account_id = :mentor ORDER BY selected_at DESC, id")
			.param("mentor", mentor)
			.query(Jobs::job)
			.list()
			.stream()
			.map(job -> new MentorJobSummaryView(job.id(), job.title(), productName(names, job.productId()), job.state(),
					Objects.requireNonNull(job.selectedAt())))
			.toList();
	}

	@GetMapping("/{jobId}")
	MentorJobView job(@PathVariable UUID jobId) {
		UUID mentor = mentor();
		Job job = mine(mentor, jobId);
		Instant selectedAt = Objects.requireNonNull(job.selectedAt());
		return new MentorJobView(job.id(), job.title(), productName(new HashMap<>(), job.productId()), job.state(),
				job.version(), job.jobTypes(), job.problem(), job.expected(), job.appUsers(), job.doneCheck(), selectedAt,
				job.state() == JobState.SELECTING ? selectedAt.plus(JobSelection.NDA_PERIOD) : null, job.code(),
				this.notices.acknowledged(mentor, Notices.COMMIT_EMAIL, Notices.COMMIT_EMAIL_VERSION));
	}

	/**
	 * JOB-1 "Từ chối" (FR-15): J5 at the version the screen loaded. The Job is Open again and no longer
	 * the Mentor's, so nothing is returned.
	 */
	@PostMapping("/{jobId}/decline")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void decline(@PathVariable UUID jobId, @Valid @RequestBody DecisionRequest body) {
		UUID mentor = mentor();
		mine(mentor, jobId);
		this.lifecycle.apply(Transition.J5, jobId, Actor.user(mentor), Trigger.USER, body.version(), Map.of());
	}

	private Job mine(UUID mentor, UUID jobId) {
		return this.jdbc.sql("SELECT " + Jobs.COLUMNS + " FROM job.job WHERE id = :id AND mentor_account_id = :mentor")
			.param("id", jobId)
			.param("mentor", mentor)
			.query(Jobs::job)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private String productName(Map<UUID, String> names, UUID productId) {
		return names.computeIfAbsent(productId, id -> this.products.find(id).map(ProductInfo::name).orElse(""));
	}

	/** The Mentor calling, unlocked (AD-19 Job command); anyone else finds nothing. */
	private UUID mentor() {
		Caller caller = this.callers.get();
		if (!caller.hasRole(Role.MENTOR)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		return mentor;
	}

}
