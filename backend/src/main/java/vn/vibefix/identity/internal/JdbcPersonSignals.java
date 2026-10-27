package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.PersonSignalMatched;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.shared.IdGenerator;

/**
 * Person-link signals (AD-19, D1): HMACs of an account's verified email addresses and of its
 * LinkedIn profile, one row per live key version, never the values themselves. Recording a signal
 * that another account already has publishes {@link PersonSignalMatched} for that account.
 * Unverified addresses never arrive here: callers record Google's verified sign-in email, GitHub's
 * verified emails, and a confirmed school email; a typed link records nothing (Story 19.3). The
 * {@code LINKEDIN} rows recorded before that stay, as every signal does, until their purge.
 */
@Component
class JdbcPersonSignals implements PersonSignals {

	enum Source {

		GOOGLE_EMAIL("EMAIL"), GITHUB_EMAIL("EMAIL"), SCHOOL_EMAIL("EMAIL"), LINKEDIN("LINKEDIN");

		final String kind;

		Source(String kind) {
			this.kind = kind;
		}

	}

	/** A LinkedIn profile address; the profile is its handle, compared without letter case. */
	private static final Pattern LINKEDIN = Pattern.compile("^https://www\\.linkedin\\.com/in/([A-Za-z0-9_%-]{3,100})/?$");

	private final JdbcClient jdbc;

	private final PersonSignalKeys keys;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcPersonSignals(JdbcClient jdbc, PersonSignalKeys keys, ApplicationEventPublisher events, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.keys = keys;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public boolean sharesPersonSignal(UUID accountId, UUID otherAccountId) {
		return this.jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM identity.person_signal mine
				    JOIN identity.person_signal theirs
				      ON theirs.kind = mine.kind AND theirs.key_version = mine.key_version AND theirs.hmac = mine.hmac
				    WHERE mine.account_id = :account AND theirs.account_id = :other)
				""").param("account", accountId).param("other", otherAccountId).query(Boolean.class).single();
	}

	/**
	 * Records the signal of {@code value} for the account, unless it has it already, and publishes
	 * a match with every other account that has it too. Runs in the caller's transaction, so the
	 * signal and its events commit with the sign-in, the code, or the save that found it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void record(UUID accountId, Source source, String value) {
		Optional<String> normalized = normalize(source, value);
		if (normalized.isEmpty()) {
			return;
		}
		recordHmacs(accountId, source, this.keys.all(source.kind, normalized.get()));
	}

	/**
	 * Records signals computed already, by key version, as {@link #record} does: a confirmed school
	 * email's, whose address is gone by then and whose HMAC the code kept (Story 19.4).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void recordHmacs(UUID accountId, Source source, Map<Integer, String> hmacs) {
		int added = 0;
		for (Map.Entry<Integer, String> hmac : hmacs.entrySet()) {
			added += this.jdbc.sql("""
					INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
					VALUES (:id, :account, :kind, :source, :hmac, :version, :now)
					ON CONFLICT (account_id, kind, key_version, hmac) DO NOTHING
					""")
				.param("id", this.ids.newId())
				.param("account", accountId)
				.param("kind", source.kind)
				.param("source", source.name())
				.param("hmac", hmac.getValue())
				.param("version", hmac.getKey())
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
		}
		if (added == 0) {
			return;
		}
		for (UUID other : holdersOf(source.kind, hmacs, accountId)) {
			this.events.publishEvent(new PersonSignalMatched(this.ids.newId(), accountId, other, source.kind));
		}
	}

	/** Deletes the account's signals: their retention ended, 24 months after its erasure (AD-24). */
	int purge(UUID accountId) {
		return this.jdbc.sql("DELETE FROM identity.person_signal WHERE account_id = :account")
			.param("account", accountId)
			.update();
	}

	private List<UUID> holdersOf(String kind, Map<Integer, String> hmacs, UUID except) {
		List<UUID> holders = new ArrayList<>();
		for (Map.Entry<Integer, String> hmac : hmacs.entrySet()) {
			this.jdbc.sql("""
					SELECT DISTINCT account_id FROM identity.person_signal
					WHERE kind = :kind AND key_version = :version AND hmac = :hmac AND account_id <> :except
					""")
				.param("kind", kind)
				.param("version", hmac.getKey())
				.param("hmac", hmac.getValue())
				.param("except", except)
				.query(UUID.class)
				.list()
				.stream()
				.filter(holder -> !holders.contains(holder))
				.forEach(holders::add);
		}
		return holders;
	}

	/** An email trimmed and in lower case; a LinkedIn link as its lower-case handle; anything else nothing. */
	static Optional<String> normalize(Source source, @Nullable String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		if (source == Source.LINKEDIN) {
			Matcher matcher = LINKEDIN.matcher(value.strip());
			return matcher.matches() ? Optional.of(matcher.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
		}
		String email = value.strip().toLowerCase(Locale.ROOT);
		return email.contains("@") ? Optional.of(email) : Optional.empty();
	}

}
