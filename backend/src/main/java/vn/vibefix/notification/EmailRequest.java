package vn.vibefix.notification;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One email to send (AD-27).
 * @param type the notification type key, which names the template {@code templates/email/{type}.txt}
 * (EXPERIENCE.md Notification Inventory), for example {@code identity.school_email_code}
 * @param causingRecordId the record this one message stands for (a code request, or a notification
 * row per recipient): the External Operation key is {@code notification.email:causingRecordId}
 * @param recipientAccountId the account the message is for, so an erasure removes it (AD-24)
 * @param to the address; purged with the parameters once the email completes
 * @param parameters the template's {@code {name}} values; user and GitHub text is escaped (AD-21)
 * @param link the platform path the message links to, in place of its template's {@code link:};
 * {@code null} keeps the template's
 */
public record EmailRequest(String type, UUID causingRecordId, UUID recipientAccountId, String to,
		EmailClass emailClass, Map<String, String> parameters, @Nullable String link) {

	/** A message that links where its template says. */
	public EmailRequest(String type, UUID causingRecordId, UUID recipientAccountId, String to, EmailClass emailClass,
			Map<String, String> parameters) {
		this(type, causingRecordId, recipientAccountId, to, emailClass, parameters, null);
	}

	public EmailRequest {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(causingRecordId, "causingRecordId");
		Objects.requireNonNull(recipientAccountId, "recipientAccountId");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(emailClass, "emailClass");
		parameters = Map.copyOf(parameters);
	}

}
