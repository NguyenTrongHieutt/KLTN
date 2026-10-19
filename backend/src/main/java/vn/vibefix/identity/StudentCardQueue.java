package vn.vibefix.identity;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** The student cards waiting for an Administrator (FR-5), as ADM-1 counts them. */
public interface StudentCardQueue {

	/**
	 * @param count cards waiting for a decision
	 * @param oldestUploadedAt when the oldest of them was uploaded; absent for an empty queue
	 */
	record Pending(long count, @Nullable Instant oldestUploadedAt) {
	}

	Pending pending();

}
