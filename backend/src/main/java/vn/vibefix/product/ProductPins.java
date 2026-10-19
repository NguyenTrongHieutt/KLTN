package vn.vibefix.product;

import java.util.UUID;

/**
 * The Jobs that pin a Product (FR-7, AD-3): {@code job} pins a Product while one of its Jobs is in
 * Selecting, Agreement, Starting, Active, or Closing, and releases it after; a pinned Product cannot
 * be deleted ({@code PRODUCT_HAS_ACTIVE_JOB}). Both run in the caller's transaction.
 */
public interface ProductPins {

	/** Pins the Product for the Job; pinning twice changes nothing. */
	void pinForJob(UUID productId, UUID jobId);

	/** Releases the Job's pin, if any. */
	void releaseForJob(UUID productId, UUID jobId);

}
