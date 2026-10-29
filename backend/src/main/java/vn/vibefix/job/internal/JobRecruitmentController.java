package vn.vibefix.job.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.ActiveAccount;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.PublicProfiles.PersonFacts;
import vn.vibefix.job.CrewRecruitment.Recruitment;
import vn.vibefix.job.internal.CrewService.Row;
import vn.vibefix.job.internal.JobRecruitment.Setting;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-9 "Tuyển thành viên" for the Job's Mentor only (FR-75, FR-81, AD-20, AD-41, UX-DR102, Story 21.4): the
 * recruitment setting with its limits, whether a member has signed (R7's warning in Agreement), and the Crew list with
 * join and leave dates and no remove action. The Customer and everyone else find nothing; the Applicant cards come from
 * {@code matching}. The Mentor's commands go through one {@code PUT} under the Job row lock, at the Crew version the
 * screen loaded (AD-23).
 */
@RestController
@Access(Gate.SIGNED_IN)
class JobRecruitmentController {

	/**
	 * "Nhận đơn xin vào nhóm": on with "Cần thêm {n} người", the Skills sought and a note, or off.
	 * @param openSlots 1 up to {@code maxSlots}, while on
	 * @param skillIds the Skills sought, in order; at most 10
	 * @param crewVersion the Crew version the screen loaded; an older one answers {@code STALE}
	 */
	record RecruitmentRequest(@NotNull Boolean on, @Nullable Integer openSlots,
			@Nullable @Size(max = JobRecruitment.MAX_SKILLS) List<@NotNull UUID> skillIds,
			@Nullable @Size(max = JobRecruitment.MAX_NOTE) String note, @NotNull Long crewVersion) {
	}

	/** A Skill sought, with whether the catalog still offers it (AD-38). */
	record RecruitmentSkillView(UUID id, String name, boolean active) {
	}

	/**
	 * One person of the Crew list (AD-41): a current member, a pick waiting to sign, or one who left (a lock reads the
	 * same, never why).
	 * @param status {@code ASSIGNED}, {@code ACTIVE}, or {@code RELEASED} for one who left
	 * @param joinedAt when they signed and joined; absent for a pick not signed yet
	 * @param leftAt when their window ended, for one who left
	 */
	record RecruitmentMemberView(UUID rowId, String name, @Nullable String githubLogin, String status,
			@Nullable Instant joinedAt, @Nullable Instant leftAt) {
	}

	/**
	 * JOB-9 as the Mentor reads it.
	 * @param available the Job is in Agreement, Starting or Active, so recruitment may change (from J4 until J8)
	 * @param recruiting the switch
	 * @param openSlots the places sought
	 * @param maxSlots the most places the Mentor may seek now: the cap minus the unreleased rows
	 * @param cap the Crew cap: 5, or 1 with {@code features.largeCrews} off
	 * @param signedMember an unlocked member has signed: without one, Agreement shows R7's warning
	 */
	record RecruitmentView(long crewVersion, JobState state, boolean available, boolean recruiting, int openSlots,
			int maxSlots, int cap, boolean signedMember, List<RecruitmentSkillView> skills, @Nullable String note,
			List<RecruitmentMemberView> crew) {
	}

	private final JobRecruitment recruitment;

	private final CrewService crews;

	private final JobRoleReader roles;

	private final PublicProfiles profiles;

	private final ActiveAccount gate;

	private final CurrentCaller callers;

	JobRecruitmentController(JobRecruitment recruitment, CrewService crews, JobRoleReader roles, PublicProfiles profiles,
			ActiveAccount gate, CurrentCaller callers) {
		this.recruitment = recruitment;
		this.crews = crews;
		this.roles = roles;
		this.profiles = profiles;
		this.gate = gate;
		this.callers = callers;
	}

	@GetMapping("/api/v1/mentor/jobs/{jobId}/recruitment")
	RecruitmentView recruitment(@PathVariable UUID jobId) {
		mentor(jobId);
		return view(this.recruitment.recruitment(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	@PutMapping("/api/v1/mentor/jobs/{jobId}/recruitment")
	RecruitmentView setRecruitment(@PathVariable UUID jobId, @Valid @RequestBody RecruitmentRequest body) {
		UUID mentor = mentor(jobId);
		Setting setting = new Setting(body.on(), body.openSlots(), body.skillIds() == null ? List.of() : body.skillIds(),
				body.note());
		return view(this.recruitment.set(mentor, jobId, body.crewVersion(), setting));
	}

	private RecruitmentView view(Recruitment recruitment) {
		UUID jobId = recruitment.jobId();
		Map<UUID, Skill> catalog = this.recruitment.skills(recruitment);
		List<RecruitmentSkillView> skills = new ArrayList<>();
		for (int i = 0; i < recruitment.skillIds().size(); i++) {
			UUID id = recruitment.skillIds().get(i);
			Skill skill = catalog.get(id);
			skills.add(new RecruitmentSkillView(id, recruitment.skillNames().get(i), skill != null && skill.active()));
		}
		List<Row> rows = this.crews.rows(jobId)
			.stream()
			.filter(row -> !row.released() || row.activatedAt() != null)
			.toList();
		Map<UUID, PersonFacts> people = new HashMap<>(this.profiles.factsOf(rows.stream().map(Row::studentId).distinct().toList()));
		List<RecruitmentMemberView> crew = rows.stream().map(row -> {
			PersonFacts person = people.get(row.studentId());
			return new RecruitmentMemberView(row.id(), person == null ? "" : person.displayName(),
					person == null ? null : person.githubLogin(), row.released() ? "RELEASED" : row.status(),
					row.activatedAt(), row.released() ? row.windowEnd() : null);
		}).toList();
		JobState state = JobState.valueOf(recruitment.state());
		boolean available = state == JobState.AGREEMENT || state == JobState.STARTING || state == JobState.ACTIVE;
		return new RecruitmentView(this.crews.crewVersion(jobId), state, available, recruitment.on(), recruitment.openSlots(),
				Math.max(0, recruitment.cap() - recruitment.unreleased()), recruitment.cap(), recruitment.signedMember(), skills,
				recruitment.note(), crew);
	}

	/**
	 * The Job's Mentor calling, by their role in the Job (AD-43), unlocked (AD-19 Job command); anyone else, the
	 * Customer included, finds nothing (AD-20).
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
