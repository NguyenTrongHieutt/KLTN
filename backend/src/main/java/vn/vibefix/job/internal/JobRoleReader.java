package vn.vibefix.job.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.internal.Jobs.Job;
import vn.vibefix.shared.Caller;

/**
 * {@code job.roleOf} and {@code job.historyOf} (AD-43): the Customer and the Mentor come from the Job row, and a
 * Crew member's relationship from {@link CrewService#crewRelationship}, read on every call. {@code job}'s own
 * policies call {@link #roleOf(Job, Caller)} with the Job they already read.
 */
@Component
class JobRoleReader implements JobRoles {

	/** J9 finished the Job: Closed, or Cancelled after Closing (FR-38, FR-40). */
	private static final String FINISHED = """
			(j.state = 'CLOSED' OR (j.state = 'CANCELLED' AND j.closing_at IS NOT NULL))""";

	/** At least one Milestone Accepted or Settled and not removed, as J10's guard reads it. */
	private static final String ACCEPTED_WORK = """
			EXISTS (SELECT 1 FROM job.milestone m WHERE m.job_id = j.id AND m.removed_at IS NULL
			    AND m.state IN ('ACCEPTED', 'SETTLED'))""";

	private final JdbcClient jdbc;

	private final CrewService crews;

	private final Administrators administrators;

	JobRoleReader(JdbcClient jdbc, CrewService crews, Administrators administrators) {
		this.jdbc = jdbc;
		this.crews = crews;
		this.administrators = administrators;
	}

	@Override
	@Transactional(readOnly = true)
	public JobRole roleOf(UUID jobId, Caller caller) {
		if (!caller.isUser()) {
			return JobRole.NONE;
		}
		return relation(jobId, caller.requireAccountId());
	}

	@Override
	@Transactional(readOnly = true)
	public JobRole roleOf(UUID jobId, UUID accountId) {
		JobRole role = relation(jobId, accountId);
		if (JobRole.NONE.equals(role) || !this.administrators.isAdministrator(accountId)) {
			return role;
		}
		return JobRole.NONE;
	}

	/** The caller's role in a Job its policy already read; an anonymous caller and an Administrator hold none. */
	JobRole roleOf(Job job, Caller caller) {
		if (!caller.isUser()) {
			return JobRole.NONE;
		}
		return relation(job.id(), job.customerId(), job.mentorId(), caller.requireAccountId());
	}

	/**
	 * Every Job where the account holds a role, newest change first, for "Công việc" (CUS-7, UX-DR86, Story 20.6):
	 * the Customer's Jobs, Drafts included, the Mentor's, and every Crew row {@link #roleOf} counts, a former
	 * member's included. A Crew row released before it was ever active holds no role, so its Job is not listed.
	 */
	@Transactional(readOnly = true)
	List<HeldJob> jobsOf(UUID accountId) {
		List<Job> jobs = this.jdbc.sql("SELECT " + Jobs.COLUMNS + """
				 FROM job.job j WHERE j.customer_account_id = :id OR j.mentor_account_id = :id
				    OR EXISTS (SELECT 1 FROM job.crew_row r WHERE r.job_id = j.id AND r.member_account_id = :id
				        AND (r.status <> 'RELEASED' OR r.activated_at IS NOT NULL))
				 ORDER BY j.updated_at DESC, j.id
				""").param("id", accountId).query(Jobs::job).list();
		List<HeldJob> held = new ArrayList<>();
		for (Job job : jobs) {
			JobRole role = relation(job.id(), job.customerId(), job.mentorId(), accountId);
			if (!JobRole.NONE.equals(role)) {
				held.add(new HeldJob(job, role));
			}
		}
		return held;
	}

	/** One Job of {@link #jobsOf} with the account's role in it. */
	record HeldJob(Job job, JobRole role) {
	}

	/**
	 * {@inheritDoc} A Job Type counts once per pair (AD-17): once per Customer in the Mentor role, and once per Mentor
	 * in the Crew role, so repeat Jobs for one Customer or under one Mentor never inflate it.
	 */
	@Override
	@Transactional(readOnly = true)
	public History historyOf(UUID accountId) {
		List<Done> mentor = done("j.customer_account_id", "j.mentor_account_id = :id", accountId);
		List<Done> crew = done("j.mentor_account_id", """
				EXISTS (SELECT 1 FROM job.crew_row r WHERE r.job_id = j.id AND r.member_account_id = :id
				    AND r.activated_at IS NOT NULL)""", accountId);
		return new History(history(mentor), history(crew));
	}

	private JobRole relation(UUID jobId, UUID accountId) {
		Optional<Parties> parties = this.jdbc
			.sql("SELECT customer_account_id, mentor_account_id FROM job.job WHERE id = :id")
			.param("id", jobId)
			.query((rs, n) -> new Parties(rs.getObject("customer_account_id", UUID.class),
					rs.getObject("mentor_account_id", UUID.class)))
			.optional();
		return parties.map(found -> relation(jobId, found.customerId(), found.mentorId(), accountId))
			.orElse(JobRole.NONE);
	}

	private JobRole relation(UUID jobId, UUID customerId, @Nullable UUID mentorId, UUID accountId) {
		if (accountId.equals(customerId)) {
			return JobRole.CUSTOMER;
		}
		if (accountId.equals(mentorId)) {
			return JobRole.MENTOR;
		}
		return JobRole.crew(this.crews.crewRelationship(jobId, accountId));
	}

	/** One Job closed with Accepted work: the other side of the pair its role counts by, and its Job Types. */
	private record Done(UUID other, String[] jobTypes) {
	}

	/** Each Job closed with Accepted work where {@code relation} holds, with the account in column {@code other}. */
	private List<Done> done(String other, String relation, UUID accountId) {
		return this.jdbc.sql("SELECT " + other + " AS other, j.job_types FROM job.job j WHERE " + relation + " AND "
				+ FINISHED + " AND " + ACCEPTED_WORK)
			.param("id", accountId)
			.query((rs, n) -> new Done(rs.getObject("other", UUID.class), types(rs.getArray("job_types"))))
			.list();
	}

	/** The Jobs' count, and per Job Type how many distinct other sides they were done with. */
	private static RoleHistory history(List<Done> jobs) {
		if (jobs.isEmpty()) {
			return RoleHistory.EMPTY;
		}
		Map<String, Set<UUID>> pairs = new HashMap<>();
		jobs.forEach(job -> Arrays.stream(job.jobTypes())
			.forEach(type -> pairs.computeIfAbsent(type, key -> new HashSet<>()).add(job.other())));
		Map<String, Integer> counts = new HashMap<>();
		pairs.forEach((type, others) -> counts.put(type, others.size()));
		return new RoleHistory(jobs.size(), counts);
	}

	private static String[] types(Array array) throws SQLException {
		return (String[]) array.getArray();
	}

	private record Parties(UUID customerId, @Nullable UUID mentorId) {
	}

}
