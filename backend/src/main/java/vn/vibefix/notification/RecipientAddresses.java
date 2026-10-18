package vn.vibefix.notification;

import java.util.Optional;
import java.util.UUID;

/**
 * Where an account's notifications are emailed (AD-3 port, recipient lookup), implemented by
 * {@code identity}; empty when no address is known, and then the account gets in-app items only.
 */
public interface RecipientAddresses {

	Optional<String> emailAddress(UUID accountId);

}
