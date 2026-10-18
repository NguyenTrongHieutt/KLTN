package vn.vibefix.notification;

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

	/** The operation priority: lower runs first. */
	public int priority() {
		return ordinal();
	}

}
