package vn.vibefix.notification.internal;

import org.jspecify.annotations.Nullable;

import vn.vibefix.notification.Audience;

/**
 * The recipient's role in the Job a to-do belongs to (AD-27, UX-DR88, Story 20.6), stored when it opens: the
 * strip and CUS-1 say "{job} · Bạn là {role}" with it, and CUS-7 counts the open to-dos of each role. It comes
 * from the audience class the opening module confirmed the recipient in; a {@code SUBJECT} or {@code ADMIN} to-do
 * has none, and neither has a {@code SPLIT_PARTY} one, whose party may be the Mentor or a Crew member.
 */
enum TodoRole {

	CUSTOMER, MENTOR, CREW;

	static @Nullable TodoRole of(Audience audience) {
		return switch (audience) {
			case CUSTOMER -> CUSTOMER;
			case MENTOR -> MENTOR;
			case CREW_ASSIGNED, CREW_ACTIVE, CREW_EVER_ACTIVE -> CREW;
			case SPLIT_PARTY, SUBJECT, ADMIN -> null;
		};
	}

}
