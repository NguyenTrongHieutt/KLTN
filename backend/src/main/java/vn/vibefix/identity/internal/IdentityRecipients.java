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
 * Administrator, and where an account's notifications are emailed (FR-48): an Administrator's
 * sign-in email, otherwise the profile's contact email once saved (FR-3, Story 19.3), the verified
 * Google email, or the GitHub primary verified email.
 */
@Component
class IdentityRecipients implements RecipientLookup, RecipientAddresses {

	private final JdbcClient jdbc;

	private final Accounts accounts;

	private final AdministratorCredentials administrators;

	IdentityRecipients(JdbcClient jdbc, Accounts accounts, AdministratorCredentials administrators) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.administrators = administrators;
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
				WHERE id = :id AND kind = 'ADMINISTRATOR' AND locked_at IS NULL AND erased_at IS NULL
				""").param("id", accountId).query(Long.class).single() > 0;
	}

	/**
	 * An Administrator's sign-in email; otherwise {@code coalesce(profile.contact_email, google_email,
	 * github_email)}.
	 */
	@Override
	public Optional<String> emailAddress(UUID accountId) {
		// An erasure deletes the profile and the credential, and clears the account's addresses, so an
		// erased account has none.
		return this.administrators.identity(accountId)
			.map(AdministratorCredentials.Identity::email)
			.or(() -> this.accounts.notificationAddress(accountId));
	}

}
