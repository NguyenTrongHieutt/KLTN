package vn.vibefix.identity;

import java.util.UUID;

/**
 * Whether an account's student verification is current (FR-5). Until Epics 20 and 21, joining a Team, a join
 * request, and a new Crew assignment are refused while it is false; running Jobs and Team membership continue.
 */
public interface StudentVerification {

	/**
	 * True while the account has a live {@code STUDENT} verification and today, in Vietnam's time zone, is on
	 * or before its last valid day (the 30 September after it was verified).
	 */
	boolean isVerified(UUID accountId);

}
