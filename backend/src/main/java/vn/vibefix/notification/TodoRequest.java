package vn.vibefix.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A to-do to open for one recipient (AD-27).
 * @param audience the recipient's audience class, checked as for notifications
 * @param subjectId the record the action is about; with the key and the recipient it names the to-do
 * @param params the values of the strip text's parameters
 * @param link the platform path the action is done on
 * @param deadlineAt when the action is due, from the owning module's Deadline; never computed by the client
 * @param jobId the Job it belongs to, so a Job's screens can show that Job's most urgent to-do
 * @param tier the ordering tier when the key has two by occasion (EXPERIENCE.md To-do Items), such as
 * {@code job.answer_closure}; {@code null} for the key's own
 */
public record TodoRequest(String key, UUID recipientAccountId, Audience audience, UUID subjectId,
		Map<String, String> params, String link, @Nullable Instant deadlineAt, @Nullable UUID jobId, @Nullable Integer tier) {

	/** A to-do at its key's own tier. */
	public TodoRequest(String key, UUID recipientAccountId, Audience audience, UUID subjectId, Map<String, String> params,
			String link, @Nullable Instant deadlineAt, @Nullable UUID jobId) {
		this(key, recipientAccountId, audience, subjectId, params, link, deadlineAt, jobId, null);
	}

	public TodoRequest {
		if (tier != null && (tier < 1 || tier > 3)) {
			throw new IllegalArgumentException("A to-do tier is 1, 2 or 3");
		}
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(recipientAccountId, "recipientAccountId");
		Objects.requireNonNull(audience, "audience");
		Objects.requireNonNull(subjectId, "subjectId");
		params = Map.copyOf(params);
		Objects.requireNonNull(link, "link");
	}

}
