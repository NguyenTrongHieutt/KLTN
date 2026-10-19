package vn.vibefix.identity;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** The submitted Mentor applications waiting for an Administrator (FR-74), as ADM-1 counts them. */
public interface MentorApplicationQueue {

	/**
	 * @param count applications submitted by Mentors still {@code PENDING}
	 * @param oldestSubmittedAt when the oldest of them was submitted; absent for an empty queue
	 */
	record Pending(long count, @Nullable Instant oldestSubmittedAt) {
	}

	Pending pending();

}
