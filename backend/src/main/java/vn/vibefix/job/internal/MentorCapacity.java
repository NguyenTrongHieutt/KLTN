package vn.vibefix.job.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.job.MentorWork;
import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;

/**
 * Whether a person can take new work as Mentor (FR-13), from the one definition of each value (AD-33): the Jobs they
 * hold as Mentor, against the FR-61 limit. MEN-3 shows the reason; the bid and J3 refuse with it. A bid asks for no
 * free Student and no Team (Story 21.2).
 */
@Component
class MentorCapacity implements MentorWork {

	/** Why a person cannot bid now (FR-13). */
	enum Reason {

		/** The person holds the most Jobs as Mentor in Selecting through Closing the rule allows. */
		MENTOR_JOB_LIMIT

	}

	record Capacity(int activeJobs, int jobLimit, List<Reason> reasons) {
	}

	private final Settings settings;

	private final JdbcClient jdbc;

	MentorCapacity(Settings settings, JdbcClient jdbc) {
		this.settings = settings;
		this.jdbc = jdbc;
	}

	Capacity of(UUID mentorId) {
		int active = activeJobCount(mentorId);
		int limit = activeJobLimit();
		return new Capacity(active, limit, active >= limit ? List.of(Reason.MENTOR_JOB_LIMIT) : List.of());
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

	/** {@inheritDoc} Every Job where the person is the Mentor counts, by {@link JobCounts#ON_TIME}. */
	@Override
	public Map<UUID, OnTime> onTimeMilestones(Collection<UUID> mentorIds) {
		Map<UUID, OnTime> onTime = new LinkedHashMap<>();
		mentorIds.forEach(id -> onTime.put(id, OnTime.NONE));
		if (mentorIds.isEmpty()) {
			return onTime;
		}
		this.jdbc.sql("SELECT j.mentor_account_id, count(*) FILTER (WHERE " + JobCounts.ON_TIME + ") AS on_time,"
				+ " count(*) AS total FROM job.milestone m JOIN job.job j ON j.id = m.job_id"
				+ " WHERE j.mentor_account_id IN (:mentors) AND " + JobCounts.ACCEPTED_DATED
				+ " GROUP BY j.mentor_account_id")
			.param("mentors", List.copyOf(new LinkedHashSet<>(mentorIds)))
			.query(rs -> {
				onTime.put(rs.getObject("mentor_account_id", UUID.class), new OnTime(rs.getInt("on_time"), rs.getInt("total")));
			});
		return onTime;
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

}
