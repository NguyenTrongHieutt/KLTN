package vn.vibefix.job;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import vn.vibefix.shared.Caller;

/**
 * {@code job.roleOf} and {@code job.historyOf} (AD-43, AD-30, AD-33): the one place a person's role in a Job is
 * derived. Every Job-scoped policy decides on the role it returns, the object state and the capabilities, never
 * on an account attribute (AD-20). Each call reads the database, with no cache.
 */
public interface JobRoles {

	/**
	 * The caller's role in the Job. An anonymous caller and an Administrator get {@link JobRole#NONE} without a
	 * read, and so does anyone on a Job that does not exist.
	 */
	JobRole roleOf(UUID jobId, Caller caller);

	/**
	 * The account's role in the Job, for code that holds only an ID. A relation found for an account of the
	 * {@code ADMINISTRATOR} kind still answers {@link JobRole#NONE}: an Administrator holds no role in any Job.
	 */
	JobRole roleOf(UUID jobId, UUID accountId);

	/**
	 * What the account did in each role (AD-30): per role, the Jobs closed with Accepted work and, per Job Type, the
	 * matches AD-17 ranks by, one per pair. A Job closed with Accepted work is one J9 finished (Closed, or Cancelled
	 * after Closing) with at least one Milestone Accepted or Settled and not removed. The Mentor role is the Job's
	 * Mentor; the Crew role is a Crew row whose active window overlapped the work of an Accepted Milestone, as its
	 * Portfolio Entry needs ({@link Crews#activeDuringAcceptedWork}), a leaver's included. It names no Job, Customer or
	 * co-member.
	 */
	History historyOf(UUID accountId);

	/**
	 * {@link #historyOf} for a page of people in one read (AD-17, SG-40), keyed by account ID; every account asked
	 * for is a key, with empty histories for none.
	 */
	Map<UUID, History> historiesOf(Collection<UUID> accountIds);

	/**
	 * One role's history.
	 * @param jobs the Jobs closed with Accepted work in the role
	 * @param jobTypes for each Job Type, how many distinct Customers (as Mentor) or Mentors (as Crew member) the
	 * account closed such a Job with: one Job Type match per pair (AD-17), so repeat Jobs for one Customer or under
	 * one Mentor count once
	 */
	record RoleHistory(int jobs, Map<String, Integer> jobTypes) {

		public static final RoleHistory EMPTY = new RoleHistory(0, Map.of());

		public RoleHistory {
			jobTypes = Map.copyOf(jobTypes);
		}

	}

	/** The account's history as a Mentor and as a Crew member, kept apart. */
	record History(RoleHistory mentor, RoleHistory crew) {

		public static final History EMPTY = new History(RoleHistory.EMPTY, RoleHistory.EMPTY);

	}

}
