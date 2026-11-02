package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-6's Crew list "Nhóm thực hiện" and JOB-H's Crew line for the Job's Mentor (FR-17, FR-75, AD-41, UX-DR102,
 * Story 21.5): the members who signed, with their join dates, the picks still waiting to sign, and the Crew version the
 * Agreement confirmation carries. Picking, withdrawing a pick and the Crew Applications live on JOB-9
 * ({@code matching}, {@link JobRecruitmentController}), and nothing here removes a member who signed (FR-75). The Customer
 * and everyone else find nothing; the Customer reads the active members' GitHub usernames on its own Agreement view.
 * <p>
 * For a Crew member (FR-77, FR-82, Story 21.6): JOB-1's header and duties before the Board exists ({@code crew/me}), and
 * "Rời công việc" ({@code crew/leave}), which only the member can do and which nobody undoes.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewController {

	/** The longest reason a member may give when leaving (FR-82). */
	static final int MAX_LEFT_NOTE = 500;

	/**
	 * One member who signed.
	 * @param joinedAt when they signed and joined
	 */
	record CrewMemberView(UUID rowId, String name, @Nullable String githubLogin, Instant joinedAt) {
	}

	/**
	 * The Mentor's Crew list.
	 * @param crewVersion the Crew version the Agreement confirmation carries (AD-23); no Customer DTO holds it
	 * @param signedMember an unlocked member has signed: without one, J6 waits (R7)
	 * @param members the {@code ACTIVE} members, in the order they joined
	 * @param waiting the picks not signed yet
	 */
	record CrewView(long crewVersion, boolean signedMember, List<CrewMemberView> members, int waiting) {
	}

	/**
	 * JOB-1 as an {@code ACTIVE} or a former member reads it, before the Board too (FR-77).
	 * @param former the member's row was released; only their window stays theirs (AD-41)
	 * @param canLeave an {@code ACTIVE} member of a Job in Agreement, Starting or Active
	 * @param codeJob a Code Job: leaving ends write access to its Working Repository
	 * @param partyMilestones the numbers N-28 names for an {@code ACTIVE} member: the Held Milestones and those with a
	 * split not {@code FINAL} where they are a party (R9); empty for a former member
	 */
	record CrewMeView(UUID jobId, String title, JobState state, String productName, String mentorName, boolean former,
			boolean canLeave, boolean codeJob, List<Integer> partyMilestones) {
	}

	/** "Rời công việc", at most {@value #MAX_LEFT_NOTE} characters of reason; blank means none. */
	record LeaveRequest(@Nullable @Size(max = MAX_LEFT_NOTE) String reason) {
	}

	private final CrewService crews;

	private final CrewDepartures departures;

	private final PublicProfiles profiles;

	private final ProductDirectory products;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	private final JobRoleReader roles;

	CrewController(CrewService crews, CrewDepartures departures, PublicProfiles profiles, ProductDirectory products,
			ActiveAccount gate, CurrentCaller callers, JobRoleReader roles) {
		this.crews = crews;
		this.departures = departures;
		this.profiles = profiles;
		this.products = products;
		this.gate = gate;
		this.callers = callers;
		this.roles = roles;
	}

	@GetMapping("/api/v1/mentor/jobs/{jobId}/crew")
	CrewView crew(@PathVariable UUID jobId) {
		mentor(jobId);
		List<Row> rows = this.crews.rows(jobId);
		List<Row> active = rows.stream().filter(Row::active).toList();
		Map<UUID, PersonFacts> people = this.profiles.factsOf(active.stream().map(Row::studentId).toList());
		List<CrewMemberView> members = active.stream().map(row -> {
			PersonFacts person = people.get(row.studentId());
			return new CrewMemberView(row.id(), person == null ? "" : person.displayName(),
					person == null ? null : person.githubLogin(),
					row.activatedAt() == null ? row.createdAt() : row.activatedAt());
		}).toList();
		int waiting = (int) rows.stream().filter(Row::assigned).count();
		return new CrewView(this.crews.crewVersion(jobId), !this.crews.countedActive(jobId).isEmpty(), members, waiting);
	}

	/**
	 * JOB-1's header, the Leave button and the former member's duties (FR-77, FR-82): the caller's own, for an
	 * {@code ACTIVE} or a former member, in any Job state. A pick not yet signed reads its assignment projection and
	 * nothing else; anyone else finds nothing.
	 */
	@GetMapping("/api/v1/jobs/{jobId}/crew/me")
	CrewMeView me(@PathVariable UUID jobId) {
		Caller caller = member();
		UUID account = caller.requireAccountId();
		Relationship kind = this.roles.roleOf(jobId, caller).crewKind();
		if (kind != Relationship.ACTIVE && kind != Relationship.FORMER) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Job job = this.crews.current(jobId);
		boolean active = kind == Relationship.ACTIVE;
		boolean leaves = job.state() == JobState.AGREEMENT || job.state() == JobState.STARTING || job.state() == JobState.ACTIVE;
		UUID mentor = job.mentorId();
		PersonFacts facts = mentor == null ? null : this.profiles.factsOf(List.of(mentor)).get(mentor);
		return new CrewMeView(jobId, job.title(), job.state(),
				this.products.find(job.productId()).map(ProductDirectory.ProductInfo::name).orElse(""),
				facts == null ? "" : facts.displayName(), !active, active && leaves, job.code(),
				active ? this.crews.partyMilestones(jobId, account) : List.of());
	}

	/**
	 * "Rời công việc" (FR-82, AD-41): at once and for good, at any time before Closing, whatever a busy marker or a freeze
	 * holds; no version, since it acts on the caller's own row.
	 * @throws ApiException {@code NOT_FOUND} with no {@code ACTIVE} row; {@code GUARD_FAILED LEAVE_CLOSED} in Closing
	 */
	@PostMapping("/api/v1/jobs/{jobId}/crew/leave")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void leave(@PathVariable UUID jobId, @Valid @RequestBody LeaveRequest body) {
		UUID account = member().requireAccountId();
		String reason = body.reason();
		this.departures.leave(account, jobId, reason == null || reason.isBlank() ? null : reason.strip());
	}

	/** An unlocked user (AD-19 Job command); an Administrator holds no role in any Job. */
	private Caller member() {
		Caller caller = this.callers.get();
		if (!caller.isUser()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		this.gate.requireActiveAccount(caller.requireAccountId());
		return caller;
	}

	/**
	 * The Job's Mentor calling, by their role in the Job (AD-43), unlocked (AD-19 Job command); anyone else finds
	 * nothing.
	 */
	private UUID mentor(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isMentor()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		UUID mentor = caller.requireAccountId();
		this.gate.requireActiveAccount(mentor);
		return mentor;
	}

}
