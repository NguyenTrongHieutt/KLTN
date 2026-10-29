package vn.vibefix.reputation;

/**
 * The role a person was rated in (AD-33, Story 21.3): each summary counts one kind of Rating only, never a mix.
 */
public enum RatingRole {

	/** As Mentor: the Customers' Ratings of the person ({@code CUSTOMER_TO_MENTOR}). */
	MENTOR,

	/** As Crew member: the Mentors' Ratings of the person ({@code MENTOR_TO_STUDENT}). */
	CREW,

	/** As Customer: the Mentors' Ratings of the person ({@code MENTOR_TO_CUSTOMER}), which "Tìm việc" shows. */
	CUSTOMER

}
