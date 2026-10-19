package vn.vibefix.job.internal;

/** What made a transition happen (AD-4, AD-9); carried by {@link vn.vibefix.job.JobTransitioned}. */
enum Trigger {

	/** A person's command: the Customer, the Mentor, or a Crew member. */
	USER,

	/** A Deadline that ran out, such as the selection lapse or Auto-acceptance. */
	AUTO,

	/** An Administrator's decision (FR-54). */
	DECISION,

	/** The System reacting to a fact: a verified payment, a repository operation, a push. */
	SYSTEM

}
