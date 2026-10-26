package vn.vibefix.notification;

import java.time.Duration;

/**
 * AD-34's priority classes: when emails compete for the daily SMTP budget, a class goes before every
 * later one (EXPERIENCE.md, Email rules: action-needed first, then one-time codes, then reminders).
 */
public enum EmailClass {

	/** Something happened or is needed now. */
	TRANSACTIONAL,

	/** A one-time code the person is waiting for. */
	ONE_TIME_CODE,

	/** A reminder of something already announced. */
	REMINDER;

	/**
	 * How long a one-time code is valid: its email is not sent once this has passed since it was requested,
	 * and the address it holds is purged then (AD-6, AD-42).
	 */
	public static final Duration ONE_TIME_CODE_LIFETIME = Duration.ofMinutes(10);

	/** The operation priority: lower runs first. */
	public int priority() {
		return ordinal();
	}

}
