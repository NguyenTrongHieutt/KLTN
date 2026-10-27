package vn.vibefix.job.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
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

	private final JdbcClient jdbc;

	MentorCapacity(Teams teams, StudentDirectory students, Settings settings, JdbcClient jdbc) {
		this.teams = teams;
		this.students = students;
		this.settings = settings;
		this.jdbc = jdbc;
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
	 * running Crew.
	 */
	@Override
	public List<UUID> freeStudents(UUID mentorId) {
		return freeStudents(this.teams.members(mentorId));
	}

	/**
	 * {@code job.activeJobCount} (AD-33): the Jobs in Selecting, Agreement, Starting, Active, or
	 * Closing whose Mentor is the account, recorded at J3.
	 */
	@Override
	public int activeJobCount(UUID mentorId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM job.job WHERE mentor_account_id = :mentor
				  AND state IN ('SELECTING', 'AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING')
				""").param("mentor", mentorId).query(Integer.class).single();
	}

	@Override
	public int activeJobLimit() {
		return this.settings.value(Rule.MENTOR_ACTIVE_JOB_LIMIT);
	}

	@Override
	public Map<UUID, Integer> activeJobCounts(Collection<UUID> mentorIds) {
		Map<UUID, Integer> counts = new HashMap<>();
		mentorIds.forEach(id -> counts.put(id, 0));
		if (mentorIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("""
				SELECT mentor_account_id, count(*) AS n FROM job.job WHERE mentor_account_id IN (:mentors)
				  AND state IN ('SELECTING', 'AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING')
				GROUP BY mentor_account_id
				""").param("mentors", List.copyOf(mentorIds)).query(rs -> {
			counts.put(rs.getObject("mentor_account_id", UUID.class), rs.getInt("n"));
		});
		return counts;
	}

	@Override
	public Set<UUID> withFreeStudents(Collection<UUID> mentorIds) {
		Map<UUID, List<UUID>> members = this.teams.membersOf(mentorIds);
		Set<UUID> free = new HashSet<>(freeStudents(members.values().stream().flatMap(List::stream).toList()));
		Set<UUID> mentors = new LinkedHashSet<>();
		members.forEach((mentor, students) -> {
			if (students.stream().anyMatch(free::contains)) {
				mentors.add(mentor);
			}
		});
		return mentors;
	}

	/** Closed Jobs only: J9 closes a Job with Accepted work (FR-14); no Job reaches it before Epic 9. */
	@Override
	public Map<UUID, Map<String, Integer>> workHistory(Collection<UUID> mentorIds) {
		Map<UUID, Map<String, Integer>> history = new HashMap<>();
		if (mentorIds.isEmpty()) {
			return history;
		}
		this.jdbc.sql("""
				SELECT mentor_account_id, type, count(DISTINCT customer_account_id) AS n
				FROM job.job, unnest(job_types) AS type
				WHERE mentor_account_id IN (:mentors) AND state = 'CLOSED'
				GROUP BY mentor_account_id, type
				""").param("mentors", List.copyOf(mentorIds)).query(rs -> {
			history.computeIfAbsent(rs.getObject("mentor_account_id", UUID.class), id -> new HashMap<>())
				.put(rs.getString("type"), rs.getInt("n"));
		});
		return history;
	}

	@Override
	public Set<UUID> experiencedIn(Collection<String> jobTypes) {
		if (jobTypes.isEmpty()) {
			return Set.of();
		}
		return new LinkedHashSet<>(this.jdbc.sql("""
				SELECT DISTINCT mentor_account_id FROM job.job
				WHERE state = 'CLOSED' AND mentor_account_id IS NOT NULL AND job_types && CAST(:types AS TEXT[])
				ORDER BY mentor_account_id
				""").param("types", jobTypes.toArray(String[]::new)).query(UUID.class).list());
	}

	/** Verified, unlocked members on no running Crew: no row of theirs is other than {@code RELEASED} (AD-33). */
	private List<UUID> freeStudents(List<UUID> members) {
		if (members.isEmpty()) {
			return List.of();
		}
		Set<UUID> onCrew = new HashSet<>(this.jdbc.sql(
				"SELECT member_account_id FROM job.crew_row WHERE member_account_id IN (:ids) AND status <> 'RELEASED'")
			.param("ids", members)
			.query(UUID.class)
			.list());
		return this.students.cards(members)
			.stream()
			.filter(card -> card.verified() && card.available() && !onCrew.contains(card.accountId()))
			.map(StudentCard::accountId)
			.toList();
	}

}
