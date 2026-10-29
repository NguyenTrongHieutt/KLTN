package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.internal.CrewService.Row;
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
 * ({@code matching}, {@link JobRecruitmentController}); there is no command here, and none removes a member who signed
 * (FR-75). The Customer and everyone else find nothing; the Customer reads the active members' GitHub usernames on its
 * own Agreement view.
 */
@RestController
@Access(Gate.SIGNED_IN)
class CrewController {

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

	private final CrewService crews;

	private final PublicProfiles profiles;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	private final JobRoleReader roles;

	CrewController(CrewService crews, PublicProfiles profiles, ActiveAccount gate, CurrentCaller callers,
			JobRoleReader roles) {
		this.crews = crews;
		this.profiles = profiles;
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
