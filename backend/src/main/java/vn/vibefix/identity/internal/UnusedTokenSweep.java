package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily sweep of kept GitHub tokens nobody needs (AD-10, AD-22): a token kept for a first Product's
 * registration that was abandoned, or one whose owner no longer has a live Product, is forgotten and revoked on
 * GitHub once it is older than {@link #UNUSED_AFTER} and no {@code GitHubLinkParticipant} keeps it. Each account
 * is its own transaction ({@link GitHubLinksImpl#forgetRepositoryToken}).
 */
@Component
class UnusedTokenSweep {

	/** How long a token kept for a registration waits for its Product. */
	static final Duration UNUSED_AFTER = Duration.ofHours(24);

	private final JdbcClient jdbc;

	private final GitHubLinksImpl links;

	private final Clock clock;

	UnusedTokenSweep(JdbcClient jdbc, GitHubLinksImpl links, Clock clock) {
		this.jdbc = jdbc;
		this.links = links;
		this.clock = clock;
	}

	/** @return the accounts whose token was looked at: kept longer than {@link #UNUSED_AFTER} */
	List<UUID> sweep() {
		List<UUID> accounts = this.jdbc.sql("SELECT account_id FROM identity.customer_token WHERE stored_at < :before ORDER BY account_id")
			.param("before", Timestamp.from(this.clock.instant().minus(UNUSED_AFTER)))
			.query(UUID.class)
			.list();
		accounts.forEach(this.links::forgetRepositoryToken);
		return accounts;
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Daily {

		private final UnusedTokenSweep sweep;

		Daily(UnusedTokenSweep sweep) {
			this.sweep = sweep;
		}

		@Scheduled(cron = "${vibefix.identity.unused-token-cron:0 40 4 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.sweep.sweep();
		}

	}

}
