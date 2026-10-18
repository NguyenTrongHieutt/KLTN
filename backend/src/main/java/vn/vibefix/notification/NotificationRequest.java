package vn.vibefix.notification;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A notification to send (AD-27).
 * @param causingRecordId the fact it reports (a transition audit entry, an application, a decision):
 * sending the same cause again adds nothing
 * @param params the values of the type's parameters, shown to every recipient
 * @param link the platform path the item and the email open, for example {@code /viec-can-lam}
 * @param jobId the Job, for Job-scoped audience classes
 * @param subjectRecordId the record a {@link Audience#SUBJECT} recipient is the subject of
 */
public record NotificationRequest(String type, UUID causingRecordId, List<Recipient> recipients,
		Map<String, String> params, String link, @Nullable UUID jobId, @Nullable UUID subjectRecordId) {

	public NotificationRequest {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(causingRecordId, "causingRecordId");
		recipients = List.copyOf(recipients);
		params = Map.copyOf(params);
		Objects.requireNonNull(link, "link");
	}

}
