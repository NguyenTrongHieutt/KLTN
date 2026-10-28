package vn.vibefix.notification;

/**
 * Who a notification type or to-do key may reach (AD-20, EXPERIENCE.md Notification Inventory).
 * Job-scoped classes need the Job and are confirmed by the module that knows the Job's people
 * ({@link RecipientLookup}); {@link #SPLIT_PARTY} also needs the Milestone whose split it names;
 * {@link #SUBJECT} needs the record the recipient is the subject of, and the sending module vouches
 * for it; {@link #ADMIN} is confirmed by {@code identity}.
 */
public enum Audience {

	/** The Customer of the Job. */
	CUSTOMER(true, false),

	/** The Mentor of the Job. */
	MENTOR(true, false),

	/** A picked Crew member who has not signed yet (AD-41, UX-DR112). */
	CREW_ASSIGNED(true, false),

	/** A Crew member during the Job. */
	CREW_ACTIVE(true, false),

	/** Anyone who was ever an active Crew member: post-Job types. */
	CREW_EVER_ACTIVE(true, false),

	/**
	 * A party of one Milestone's split (AD-20, UX-DR112): the Mentor or a Crew member holding a share of the Crew
	 * Terms version pinned to that Milestone, until Story 22.1 brings the per-Milestone split. Never the Customer.
	 */
	SPLIT_PARTY(true, true),

	/** A named invitee, requester, applicant, reporter, or account owner. */
	SUBJECT(false, false),

	/** Every Administrator. */
	ADMIN(false, false);

	private final boolean jobScoped;

	private final boolean milestoneScoped;

	Audience(boolean jobScoped, boolean milestoneScoped) {
		this.jobScoped = jobScoped;
		this.milestoneScoped = milestoneScoped;
	}

	public boolean jobScoped() {
		return this.jobScoped;
	}

	/** Whether a recipient of this class needs the Milestone too ({@link #SPLIT_PARTY}). */
	public boolean milestoneScoped() {
		return this.milestoneScoped;
	}

}
