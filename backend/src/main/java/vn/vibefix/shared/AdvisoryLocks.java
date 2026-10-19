package vn.vibefix.shared;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Transaction-level advisory locks (AD-4): held until the caller's transaction ends, so two commands
 * that must not interleave for one account (for example FR-13's free-Student and Job-limit guards at
 * an Application and at J3) wait for each other. The lock order of AD-4 puts the Mentor lock after
 * the Job row and the Product lock.
 */
public final class AdvisoryLocks {

	private AdvisoryLocks() {
	}

	/** Locks {@code accountId} for the rest of the caller's transaction. */
	public static void lockAccount(JdbcClient jdbc, UUID accountId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
			.param("key", accountId.getMostSignificantBits() ^ accountId.getLeastSignificantBits())
			.query((rs, n) -> null)
			.optional();
	}

}
