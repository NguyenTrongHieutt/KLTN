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
 */
public record TodoRequest(String key, UUID recipientAccountId, Audience audience, UUID subjectId,
		Map<String, String> params, String link, @Nullable Instant deadlineAt, @Nullable UUID jobId) {

	public TodoRequest {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(recipientAccountId, "recipientAccountId");
		Objects.requireNonNull(audience, "audience");
		Objects.requireNonNull(subjectId, "subjectId");
		params = Map.copyOf(params);
		Objects.requireNonNull(link, "link");
	}

}
