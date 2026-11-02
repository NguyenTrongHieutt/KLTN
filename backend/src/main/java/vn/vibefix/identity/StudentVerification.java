package vn.vibefix.identity;

import java.util.UUID;

/**
 * Whether an account's student verification is current (FR-5). It is a fact the person can show, and gates nothing.
 */
public interface StudentVerification {

	/**
	 * True while the account has a live {@code STUDENT} verification and today, in Vietnam's time zone, is on
	 * or before its last valid day (the 30 September after it was verified).
	 */
	boolean isVerified(UUID accountId);

}
