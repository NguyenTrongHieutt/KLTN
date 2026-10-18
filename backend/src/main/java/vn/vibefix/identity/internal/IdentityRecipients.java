package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.RecipientAddresses;
import vn.vibefix.notification.RecipientLookup;

/**
 * {@code identity}'s answers to {@code notification} (AD-3 recipient lookup): who is an
 * Administrator, and where an account's notifications are emailed (its GitHub primary verified
 * email, FR-48), or a Customer's contact email once it is saved (FR-3).
 */
@Component
class IdentityRecipients implements RecipientLookup, RecipientAddresses {

	private final JdbcClient jdbc;

	private final Accounts accounts;

	private final Profiles profiles;

	IdentityRecipients(JdbcClient jdbc, Accounts accounts, Profiles profiles) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.profiles = profiles;
	}

	@Override
	public Set<Audience> audiences() {
		return Set.of(Audience.ADMIN);
	}

	/** An unlocked Administrator account (AD-19). */
	@Override
	public boolean holds(UUID accountId, Audience audience, @Nullable UUID jobId) {
		if (audience != Audience.ADMIN) {
			return false;
		}
		return this.jdbc.sql("""
				SELECT count(*) FROM identity.account
				WHERE id = :id AND role = 'ADMINISTRATOR' AND locked_at IS NULL AND erased_at IS NULL
				""").param("id", accountId).query(Long.class).single() > 0;
	}

	/** A Customer's contact email once saved (FR-3), otherwise the GitHub primary verified email. */
	@Override
	public Optional<String> emailAddress(UUID accountId) {
		// An erasure deletes the profile and clears the GitHub email, so an erased account has neither.
		return this.profiles.customer(accountId)
			.map(Profiles.CustomerProfile::contactEmail)
			.or(() -> this.accounts.notificationAddress(accountId));
	}

}
