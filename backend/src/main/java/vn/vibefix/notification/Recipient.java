package vn.vibefix.notification;

import java.util.Objects;
import java.util.UUID;

/** One recipient of a notification, with the audience class the sender says they belong to. */
public record Recipient(UUID accountId, Audience audience) {

	public Recipient {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(audience, "audience");
	}

}
