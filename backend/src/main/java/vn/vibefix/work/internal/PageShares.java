package vn.vibefix.work.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.job.JobRoles;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * "Chia sẻ tài liệu cũ" on JOB-6 (FR-70, UX-DR59): the Customer chooses, Job by Job, which other Jobs of the
 * same Product share their customer-visible Pages with this Job's Mentor and {@code ACTIVE} Crew. Nothing is
 * shared until the Customer chooses it; Jobs with an Assessment Report come first. Sharing changes while the
 * Job is Agreement, Starting, Active, or Closing; {@link WorkAccess} decides who reads, at each request.
 */
@Component
class PageShares {

	static final String PAGES_SHARED = "work.pages_shared";

	/** The Job's states in which the Customer may change what it shares. */
	private static final Set<String> CHANGEABLE = Set.of("AGREEMENT", "STARTING", "ACTIVE", "CLOSING");

	/**
	 * Another Job of the Product with customer-visible Pages.
	 * @param pages its customer-visible Pages, hidden ones left out
	 */
	record Option(JobPostState job, boolean assessmentReport, int pages, boolean shared) {
	}

	/** The checklist: whether the Customer may change it now, and the Jobs it offers. */
	record Choices(boolean editable, List<Option> jobs) {

		Choices {
			jobs = List.copyOf(jobs);
		}

	}

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final Crews crews;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final JobRoles roles;

	PageShares(JdbcClient jdbc, JobPosts posts, Crews crews, Notifications notifications, AuditLog auditLog,
			IdGenerator ids, Clock clock, JobRoles roles) {
		this.roles = roles;
		this.jdbc = jdbc;
		this.posts = posts;
		this.crews = crews;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/** The checklist, for the Job's Customer only. */
	@Transactional(readOnly = true)
	Choices choices(Caller caller, UUID jobId) {
		return choices(ownJob(caller, jobId));
	}

	/**
	 * "Lưu chia sẻ": shares exactly the chosen Jobs. A newly shared Job is audited as {@code work.pages_shared}
	 * and tells the Mentor and the {@code ACTIVE} Crew, once per save; an unchecked one is removed and audited as
	 * {@code work.pages_unshared}. {@code GUARD_FAILED SHARING_CLOSED} outside the changeable states;
	 * {@code VALIDATION NOT_SHAREABLE} for a Job the checklist does not offer.
	 */
	@Transactional
	Choices share(Caller caller, UUID jobId, List<UUID> chosen) {
		JobPostState job = ownJob(caller, jobId);
		Choices before = choices(job);
		if (!before.editable()) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.SHARING_CLOSED);
		}
		Set<UUID> offered = new HashSet<>();
		before.jobs().forEach(option -> offered.add(option.job().jobId()));
		Set<UUID> wanted = new LinkedHashSet<>(chosen);
		if (!offered.containsAll(wanted)) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.NOT_SHAREABLE);
		}
		List<UUID> current = this.jdbc.sql("SELECT source_job_id FROM work.page_share WHERE job_id = :job")
			.param("job", jobId)
			.query(UUID.class)
			.list();
		List<UUID> removed = new ArrayList<>();
		for (UUID source : current) {
			if (!wanted.contains(source) && this.jdbc.sql("DELETE FROM work.page_share WHERE job_id = :job AND source_job_id = :source")
				.param("job", jobId)
				.param("source", source)
				.update() == 1) {
				removed.add(source);
			}
		}
		List<UUID> added = new ArrayList<>();
		Timestamp now = Timestamp.from(this.clock.instant());
		for (UUID source : wanted) {
			if (this.jdbc.sql("""
					INSERT INTO work.page_share (job_id, source_job_id, created_at) VALUES (:job, :source, :now)
					ON CONFLICT DO NOTHING
					""").param("job", jobId).param("source", source).param("now", now).update() == 1) {
				added.add(source);
			}
		}
		UUID customer = caller.requireAccountId();
		for (UUID source : added) {
			this.auditLog.record(AuditEntry.of(AuditActor.user(customer), PAGES_SHARED, AuditObject.of("work.board", source))
				.forJob(jobId));
		}
		for (UUID source : removed) {
			this.auditLog.record(AuditEntry.of(AuditActor.user(customer), "work.pages_unshared",
					AuditObject.of("work.board", source)).forJob(jobId));
		}
		if (!added.isEmpty()) {
			tell(job);
		}
		return choices(job);
	}

	private Choices choices(JobPostState job) {
		boolean editable = job.mentorId() != null && CHANGEABLE.contains(job.state());
		List<JobPostState> others = this.posts.productJobs(job.productId())
			.stream()
			.filter(other -> !other.jobId().equals(job.jobId()) && other.customerId().equals(job.customerId()))
			.toList();
		if (others.isEmpty()) {
			return new Choices(editable, List.of());
		}
		Map<UUID, Integer> pages = new HashMap<>();
		Set<UUID> reports = new HashSet<>();
		this.jdbc.sql("SELECT job_id, count(*) AS pages, bool_or(template = 'ASSESSMENT_REPORT') AS report FROM work.page"
				+ " WHERE job_id IN (:jobs) AND visibility = 'CUSTOMER' AND " + Pages.SHOWN + " GROUP BY job_id")
			.param("jobs", others.stream().map(JobPostState::jobId).toList())
			.query((rs, row) -> {
				UUID id = rs.getObject("job_id", UUID.class);
				pages.put(id, rs.getInt("pages"));
				if (rs.getBoolean("report")) {
					reports.add(id);
				}
				return id;
			})
			.list();
		Set<UUID> shared = new HashSet<>(this.jdbc.sql("SELECT source_job_id FROM work.page_share WHERE job_id = :job")
			.param("job", job.jobId())
			.query(UUID.class)
			.list());
		List<Option> options = others.stream()
			.filter(other -> pages.containsKey(other.jobId()))
			.map(other -> new Option(other, reports.contains(other.jobId()), pages.get(other.jobId()),
					shared.contains(other.jobId())))
			.sorted(Comparator.comparing(option -> !option.assessmentReport()))
			.toList();
		return new Choices(editable, options);
	}

	/** The Job of its Customer calling, by their role in it (AD-43); anyone else finds nothing. */
	private JobPostState ownJob(Caller caller, UUID jobId) {
		if (!this.roles.roleOf(jobId, caller).isCustomer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return this.posts.post(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private void tell(JobPostState job) {
		List<Recipient> recipients = new ArrayList<>();
		recipients.add(new Recipient(job.mentorId(), Audience.MENTOR));
		this.crews.crewOf(job.jobId(), Selection.ACTIVE)
			.forEach(member -> recipients.add(new Recipient(member.studentId(), Audience.CREW_ACTIVE)));
		this.notifications.send(new NotificationRequest(PAGES_SHARED, this.ids.newId(), recipients, Map.of(),
				"/cong-viec/" + job.jobId() + "/tai-lieu-cu", job.jobId(), null));
	}

}
