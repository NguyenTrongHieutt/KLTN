package vn.vibefix.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Who besides the Customer and Administrators reads a Customer's contact details (FR-3, AD-3): the
 * Mentor of each of the Customer's Jobs from Agreement until the Job is Closed or Cancelled, never a
 * Crew member. {@code job} pushes the grant down in its own transaction and ends it; {@code identity}
 * decides on its own data.
 */
public interface ContactVisibility {

	/** Lets {@code mentorId} read {@code customerId}'s contact details for {@code jobId}; repeating it changes nothing. */
	void grant(UUID customerId, UUID mentorId, UUID jobId);

	/** Ends the grant for {@code jobId} at {@code at}. */
	void end(UUID customerId, UUID mentorId, UUID jobId, Instant at);

}
