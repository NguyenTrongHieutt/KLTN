package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.MentorWork;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.team.Teams;

/**
 * Whether a Mentor can take new work (FR-13), from the one definition of each value (AD-33): the free
 * Students of their Team and the Jobs they hold, against the FR-61 limit. MEN-2 and MEN-3 show the
 * reasons; the Application (Story 3.6) and J3 refuse with them.
 */
@Component
class MentorCapacity implements MentorWork {

	/** Why a Mentor cannot apply now (FR-13). */
	enum Reason {

		/** No verified, unlocked Student of the Team is free. */
		NO_FREE_STUDENT,

		/** The Mentor holds the most Jobs in Selecting through Closing the rule allows. */
		MENTOR_JOB_LIMIT

	}

	record Capacity(int teamSize, int freeStudents, int activeJobs, int jobLimit, List<Reason> reasons) {
	}

	private final Teams teams;

	private final StudentDirectory students;

	private final Settings settings;

	MentorCapacity(Teams teams, StudentDirectory students, Settings settings) {
		this.teams = teams;
		this.students = students;
		this.settings = settings;
	}

	Capacity of(UUID mentorId) {
		List<UUID> members = this.teams.members(mentorId);
		int free = freeStudents(members).size();
		int active = activeJobCount(mentorId);
		int limit = activeJobLimit();
		List<Reason> reasons = new ArrayList<>();
		if (free == 0) {
			reasons.add(Reason.NO_FREE_STUDENT);
		}
		if (active >= limit) {
			reasons.add(Reason.MENTOR_JOB_LIMIT);
		}
		return new Capacity(members.size(), free, active, limit, List.copyOf(reasons));
	}

	/**
	 * {@code job.freeStudents} (AD-33): the verified, unlocked members of the Mentor's Team on no
	 * running Crew. No Crew row exists before the Agreement (Epic 4), so every such member is free.
	 */
	@Override
	public List<UUID> freeStudents(UUID mentorId) {
		return freeStudents(this.teams.members(mentorId));
	}

	/**
	 * {@code job.activeJobCount} (AD-33): the Jobs in Selecting, Agreement, Starting, Active, or
	 * Closing whose Mentor is the account. A Job has a Mentor from J3 on (Story 3.7), so there are none yet.
	 */
	@Override
	public int activeJobCount(UUID mentorId) {
		return 0;
	}

	@Override
	public int activeJobLimit() {
		return this.settings.value(Rule.MENTOR_ACTIVE_JOB_LIMIT);
	}

	private List<UUID> freeStudents(List<UUID> members) {
		return this.students.cards(members)
			.stream()
			.filter(card -> card.verified() && card.available())
			.map(StudentCard::accountId)
			.toList();
	}

}
