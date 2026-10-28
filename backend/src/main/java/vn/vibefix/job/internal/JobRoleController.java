package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.job.JobRole;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * The caller's role in each Job as the one shell reads it (AD-43, UX-DR85 to UX-DR87, Story 20.6): JOB-H's
 * "Bạn là: …" line and the variant of every Job screen come from {@code GET /jobs/{jobId}/role}, and "Công việc"
 * (CUS-7) lists every Job the caller holds a role in from {@code GET /my-jobs}. Both answer an unlocked user only;
 * a Job where the caller holds no role, and every Job for an Administrator, is not found (SG-31). Open bids are
 * subject relations, not roles: {@code GET /mentor/applications} lists them.
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobRoleController {

	/** The role in one Job (AD-43). */
	enum RoleInJob {

		CUSTOMER, MENTOR, CREW

	}

	/** A Crew member's relationship (AD-41); a former member's reads "· đã rời". */
	enum CrewKind {

		ASSIGNED, ACTIVE, FORMER

	}

	/**
	 * The caller's role in the Job.
	 * @param crewKind the Crew relationship, for {@code CREW} only
	 */
	record JobRoleView(RoleInJob role, @Nullable CrewKind crewKind) {
	}

	/**
	 * One Job of "Công việc", whatever the role.
	 * @param crewKind the Crew relationship, for {@code CREW} only
	 */
	record MyJobView(UUID jobId, String title, String productName, JobState state, RoleInJob role,
			@Nullable CrewKind crewKind, Instant updatedAt) {
	}

	private final JobRoleReader roles;

	private final ProductDirectory products;

	private final CurrentCaller callers;

	private final ActiveAccount active;

	JobRoleController(JobRoleReader roles, ProductDirectory products, CurrentCaller callers, ActiveAccount active) {
		this.roles = roles;
		this.products = products;
		this.callers = callers;
		this.active = active;
	}

	/** JOB-H's "Bạn là: …" (UX-DR87): any Job state; a Job where the caller holds no role is not found. */
	@GetMapping("/api/v1/jobs/{jobId}/role")
	JobRoleView role(@PathVariable UUID jobId) {
		Caller caller = user();
		JobRole role = this.roles.roleOf(jobId, caller);
		RoleInJob name = name(role);
		if (name == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new JobRoleView(name, kind(role));
	}

	/** CUS-7 (UX-DR86): every Job where the caller is the Customer, the Mentor or a Crew member, newest first. */
	@GetMapping("/api/v1/my-jobs")
	List<MyJobView> myJobs() {
		UUID account = user().requireAccountId();
		Map<UUID, String> names = new HashMap<>();
		return this.roles.jobsOf(account).stream().map(held -> {
			Jobs.Job job = held.job();
			String product = names.computeIfAbsent(job.productId(),
					id -> this.products.find(id).map(ProductDirectory.ProductInfo::name).orElse(""));
			return new MyJobView(job.id(), job.title(), product, job.state(), Objects.requireNonNull(name(held.role())),
					kind(held.role()), job.updatedAt());
		}).toList();
	}

	/** An unlocked user (AD-19, read on every request); an Administrator holds no role in any Job. */
	private Caller user() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.active.requireActiveAccount(caller.requireAccountId());
		return caller;
	}

	private static @Nullable RoleInJob name(JobRole role) {
		return switch (role) {
			case JobRole.Customer customer -> RoleInJob.CUSTOMER;
			case JobRole.Mentor mentor -> RoleInJob.MENTOR;
			case JobRole.Crew crew -> RoleInJob.CREW;
			case JobRole.None none -> null;
		};
	}

	private static @Nullable CrewKind kind(JobRole role) {
		return switch (role.crewKind()) {
			case ASSIGNED -> CrewKind.ASSIGNED;
			case ACTIVE -> CrewKind.ACTIVE;
			case FORMER -> CrewKind.FORMER;
			case NONE -> null;
		};
	}

}
