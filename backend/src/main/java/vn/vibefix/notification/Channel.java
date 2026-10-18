package vn.vibefix.notification;

/** Where a notification type goes (EXPERIENCE.md Notification Inventory); there are no per-type settings. */
public enum Channel {

	/** Purely informational types. */
	IN_APP,

	/** Every FR-48 event, its reminders, and action-needed events. */
	IN_APP_AND_EMAIL,

	/** Messages with no in-app item, such as a locked account's notice. */
	EMAIL_ONLY;

	public boolean inApp() {
		return this != EMAIL_ONLY;
	}

	public boolean email() {
		return this != IN_APP;
	}

}
