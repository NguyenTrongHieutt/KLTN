package vn.vibefix.notification.internal;

import org.jspecify.annotations.Nullable;

import vn.vibefix.notification.Audience;

/**
 * The recipient's role in the Job a to-do belongs to (AD-27, UX-DR88, Story 20.6), stored when it opens: the
 * strip and CUS-1 say "{job} · Bạn là {role}" with it, and CUS-7 counts the open to-dos of each role. It comes
 * from the audience class the opening module confirmed the recipient in; a {@code SUBJECT} or {@code ADMIN} to-do
 * has none. A {@code SPLIT_PARTY} is a party other than the Mentor, who is reached as {@code MENTOR}: a Crew member,
 * leavers included. The Customer of a Self-mentored Job holds one role, {@link #CUSTOMER_MENTOR}, which {@code job}
 * confirms through the recipient lookup, because {@code notification} cannot read a Job's mode.
 */
enum TodoRole {

	CUSTOMER, MENTOR, CREW,

	/**
	 * The Customer of a Self-mentored Job (AD-47 exception 12 c, Story 25.4): one role, whichever of the two classes the
	 * to-do was opened to.
	 */
	CUSTOMER_MENTOR;

	/** The role of a to-do opened to {@code audience}; {@code combined} says its recipient holds the Job's combined role. */
	static @Nullable TodoRole of(Audience audience, boolean combined) {
		TodoRole role = of(audience);
		return combined && (role == CUSTOMER || role == MENTOR) ? CUSTOMER_MENTOR : role;
	}

	static @Nullable TodoRole of(Audience audience) {
		return switch (audience) {
			case CUSTOMER -> CUSTOMER;
			case MENTOR -> MENTOR;
			case CREW_ASSIGNED, CREW_ACTIVE, CREW_EVER_ACTIVE, SPLIT_PARTY -> CREW;
			case SUBJECT, ADMIN -> null;
		};
	}

}
