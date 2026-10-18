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
 * email, FR-48). A Customer's contact email takes over in Story 1.8.
 */
@Component
class IdentityRecipients implements RecipientLookup, RecipientAddresses {

	private final JdbcClient jdbc;

	private final Accounts accounts;

	IdentityRecipients(JdbcClient jdbc, Accounts accounts) {
		this.jdbc = jdbc;
		this.accounts = accounts;
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

	@Override
	public Optional<String> emailAddress(UUID accountId) {
		return this.accounts.notificationAddress(accountId);
	}

}
