package vn.vibefix.admin.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditReader;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.job.JobOversight;
import vn.vibefix.job.JobOversight.Flag;
import vn.vibefix.job.JobOversight.JobLine;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-5 and ADM-8 (FR-63, FR-68, SG-28, UX-DR69): Jobs by state, overdue, or frozen, each with its
 * Milestones and the people's names, and the read-only Audit Log by Job or by user. No Job Chat or Crew
 * Chat is reachable from here.
 */
@RestController
@Access(Gate.ADMINISTRATOR)
class AdminOversightController {

	record OversightMilestoneView(UUID id, int number, String title, String state, @Nullable LocalDate dueOn,
			@Nullable Instant overdueSince) {
	}

	record OversightJobView(UUID id, String title, String state, @Nullable String customerName,
			@Nullable String mentorName, boolean frozen, boolean overdue, Instant updatedAt,
			List<OversightMilestoneView> milestones) {
	}

	record OversightJobsView(List<OversightJobView> items, long total, int page, int size) {
	}

	record AuditObjectView(String type, UUID id) {
	}

	/** @param actorName the acting account's name; {@code null} with {@code system} for the System */
	record AuditEntryView(UUID id, Instant occurredAt, boolean system, @Nullable UUID actorAccountId,
			@Nullable String actorName, String action, @Nullable UUID jobId, List<AuditObjectView> objects) {
	}

	record AuditPageView(List<AuditEntryView> items, long total, int page, int size) {
	}

	private final JobOversight jobs;

	private final AuditReader audit;

	private final AccountAdministration accounts;

	AdminOversightController(JobOversight jobs, AuditReader audit, AccountAdministration accounts) {
		this.jobs = jobs;
		this.audit = audit;
		this.accounts = accounts;
	}

	@GetMapping("/api/v1/admin/jobs")
	OversightJobsView jobs(@RequestParam(required = false) @Nullable String state,
			@RequestParam(required = false) @Nullable Flag flag, @RequestParam(defaultValue = "0") int page) {
		JobOversight.Page found = this.jobs.search(state, flag, page);
		Map<UUID, String> names = this.accounts.names(people(found.items()));
		return new OversightJobsView(found.items().stream().map(job -> view(job, names)).toList(), found.total(), found.page(),
				found.size());
	}

	@GetMapping("/api/v1/admin/jobs/{jobId}")
	OversightJobView job(@PathVariable UUID jobId) {
		JobLine job = this.jobs.job(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return view(job, this.accounts.names(people(List.of(job))));
	}

	@GetMapping("/api/v1/admin/audit")
	AuditPageView audit(@RequestParam(required = false) @Nullable UUID jobId,
			@RequestParam(required = false) @Nullable UUID accountId, @RequestParam(defaultValue = "0") int page) {
		AuditReader.Page found = this.audit.search(jobId, accountId, page);
		Set<UUID> actors = new HashSet<>();
		found.items().forEach(entry -> {
			if (entry.actorAccountId() != null) {
				actors.add(entry.actorAccountId());
			}
		});
		Map<UUID, String> names = this.accounts.names(actors);
		return new AuditPageView(found.items()
			.stream()
			.map(entry -> new AuditEntryView(entry.id(), entry.occurredAt(), entry.actorAccountId() == null,
					entry.actorAccountId(), entry.actorAccountId() == null ? null : names.get(entry.actorAccountId()),
					entry.action(), entry.jobId(),
					entry.objects().stream().map(object -> new AuditObjectView(object.type(), object.id())).toList()))
			.toList(), found.total(), found.page(), found.size());
	}

	private static Set<UUID> people(List<JobLine> jobs) {
		Set<UUID> people = new HashSet<>();
		jobs.forEach(job -> {
			people.add(job.customerId());
			if (job.mentorId() != null) {
				people.add(job.mentorId());
			}
		});
		return people;
	}

	private static OversightJobView view(JobLine job, Map<UUID, String> names) {
		return new OversightJobView(job.id(), job.title(), job.state(), names.get(job.customerId()),
				job.mentorId() == null ? null : names.get(job.mentorId()), job.frozen(), job.overdue(), job.updatedAt(),
				job.milestones()
					.stream()
					.map(milestone -> new OversightMilestoneView(milestone.id(), milestone.number(), milestone.title(),
							milestone.state(), milestone.dueOn(), milestone.overdueSince()))
					.toList());
	}

}
