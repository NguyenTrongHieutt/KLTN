package vn.vibefix.job;

import java.util.Optional;
import java.util.UUID;

/** Open Job Posts as other modules read them (FR-12, AD-3). */
public interface JobPosts {

	/**
	 * The Customer of an Open Job Post that is not hidden (FR-60), for the Rating summary MEN-3 shows;
	 * empty for a Draft, a hidden Job Post, or a Job past Open.
	 */
	Optional<UUID> openCustomer(UUID jobId);

}
