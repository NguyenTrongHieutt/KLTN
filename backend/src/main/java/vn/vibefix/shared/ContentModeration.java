package vn.vibefix.shared;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * FR-60, AD-2: content an Administrator may hide on ADM-6. The module that owns a kind implements it
 * and filters its own reads; {@code admin} lists, hides, audits, and tells the author.
 */
public interface ContentModeration {

	/** {@code PRODUCT}, {@code JOB_POST}, {@code PAGE}, or {@code RATING}. */
	String kind();

	/**
	 * One item as ADM-6 lists it.
	 * @param authorId who reads why it was hidden: the Customer, the Page's creator, or the rater
	 */
	record Item(UUID id, String title, @Nullable String excerpt, @Nullable UUID authorId, Instant createdAt,
			@Nullable Instant hiddenAt, @Nullable String hiddenReason) {
	}

	/** The newest items that are visible, or hidden, at most {@code limit}. */
	List<Item> items(boolean hidden, int limit);

	/**
	 * Hides the item with the reason its author reads.
	 * @return the item as it now is; empty when there is none, or it is hidden already
	 */
	Optional<Item> hide(UUID id, String reason);

}
