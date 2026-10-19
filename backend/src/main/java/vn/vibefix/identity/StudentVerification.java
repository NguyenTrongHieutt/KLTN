package vn.vibefix.identity;

import java.util.UUID;

/**
 * Whether a Student's verification is current (FR-5). Later epics refuse joining a Team, a join
 * request, and a new Crew assignment while it is false; running Jobs and Team membership continue.
 */
public interface StudentVerification {

	/**
	 * True while the account has a verification and today, in Vietnam's time zone, is on or before
	 * its last valid day (the 30 September after it was verified).
	 */
	boolean isVerified(UUID accountId);

}
