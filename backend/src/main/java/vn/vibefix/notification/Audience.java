package vn.vibefix.notification;

/**
 * Who a notification type or to-do key may reach (AD-20, EXPERIENCE.md Notification Inventory).
 * Job-scoped classes need the Job and are confirmed by the module that knows the Job's people
 * ({@link RecipientLookup}); {@link #SUBJECT} needs the record the recipient is the subject of, and
 * the sending module vouches for it; {@link #ADMIN} is confirmed by {@code identity}.
 */
public enum Audience {

	/** The Customer of the Job. */
	CUSTOMER(true),

	/** The Mentor of the Job. */
	MENTOR(true),

	/** A Crew member who has not confirmed yet. */
	CREW_ASSIGNED(true),

	/** A Crew member during the Job. */
	CREW_ACTIVE(true),

	/** Anyone who was ever an active Crew member: post-Job types. */
	CREW_EVER_ACTIVE(true),

	/** A named invitee, requester, applicant, reporter, or account owner. */
	SUBJECT(false),

	/** Every Administrator. */
	ADMIN(false);

	private final boolean jobScoped;

	Audience(boolean jobScoped) {
		this.jobScoped = jobScoped;
	}

	public boolean jobScoped() {
		return this.jobScoped;
	}

}
