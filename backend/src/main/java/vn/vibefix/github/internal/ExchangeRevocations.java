package vn.vibefix.github.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubExchangeRevokeUnknown;
import vn.vibefix.github.GitHubExchangeRevoked;
import vn.vibefix.shared.IdGenerator;

/**
 * Revokes the tokens the Actions exchange issued (AD-44, AD-46): every poll, each exchanged correlation's run
 * is read by its ID with the dispatching App's token, and its token is revoked once the run completed, or
 * {@link ActionsRuns#RUN_TIMEOUT} after it was issued. Revoked, the correlation becomes {@code REVOKED} and
 * {@link GitHubExchangeRevoked} is published; a revocation GitHub does not confirm is tried again next poll.
 * A correlation whose token is not in memory, because vibefix restarted while it was out, becomes
 * {@code REVOKE_UNKNOWN} once, with {@link GitHubExchangeRevokeUnknown}: GitHub expires that token within the
 * hour. Open correlations past their expiry become {@code EXPIRED}.
 */
@Component
class ExchangeRevocations {

	private static final Logger log = LoggerFactory.getLogger(ExchangeRevocations.class);

	private final ExchangeCorrelations correlations;

	private final IssuedTokens issued;

	private final GitHubAppsClient apps;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	private final Clock clock;

	ExchangeRevocations(ExchangeCorrelations correlations, IssuedTokens issued, GitHubAppsClient apps,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager, IdGenerator ids, Clock clock) {
		this.correlations = correlations;
		this.issued = issued;
		this.apps = apps;
		this.events = events;
		this.transactions = new TransactionTemplate(transactionManager);
		this.ids = ids;
		this.clock = clock;
	}

	/** One poll. */
	void poll() {
		Integer expired = this.transactions.execute(status -> this.correlations.expire(this.clock.instant()));
		if (expired != null && expired > 0) {
			log.info("{} exchange correlation(s) expired unused", expired);
		}
		List<ExchangeCorrelations.Row> out = this.correlations.exchanged();
		for (ExchangeCorrelations.Row row : out) {
			try {
				revokeIfOver(row);
			}
			catch (RuntimeException ex) {
				log.warn("The token of correlation {} was not revoked this poll: {}", row.id(), ex.getClass().getSimpleName());
			}
		}
	}

	private void revokeIfOver(ExchangeCorrelations.Row row) {
		Optional<InstallationToken> token = this.issued.get(row.id());
		if (token.isEmpty()) {
			this.transactions.executeWithoutResult(status -> {
				if (this.correlations.revokeUnknown(row.id())) {
					this.events.publishEvent(new GitHubExchangeRevokeUnknown(this.ids.newId(), row.id(), row.operation()));
				}
			});
			log.warn("The token of correlation {} is unknown since a restart: GitHub expires it within the hour", row.id());
			return;
		}
		Instant now = this.clock.instant();
		boolean timedOut = row.issuedAt() != null && !now.isBefore(row.issuedAt().plus(ActionsRuns.RUN_TIMEOUT));
		if (!timedOut && !completed(row)) {
			return;
		}
		int status = this.apps.revokeStatus(token.get());
		// 401: GitHub no longer knows the token, which is what a revocation wants.
		if (status != 204 && status != 401) {
			log.warn("The token of correlation {} was not revoked: HTTP {}; tried again next poll", row.id(), status);
			return;
		}
		this.transactions.executeWithoutResult(transaction -> {
			if (this.correlations.revoked(row.id(), now)) {
				this.events.publishEvent(new GitHubExchangeRevoked(this.ids.newId(), row.id(), row.operation()));
			}
		});
		// Forgotten only once the row says REVOKED: after a failed update the next poll revokes it again (401).
		this.issued.remove(row.id());
		log.info("The token of correlation {} was revoked: its run {}", row.id(), timedOut ? "timed out" : "completed");
	}

	/** Whether the run completed, as the dispatching App reads it; false when it cannot be read. */
	private boolean completed(ExchangeCorrelations.Row row) {
		if (row.runId() == null || !App.PLATFORM.name().equals(row.app())) {
			return false;
		}
		try {
			return this.apps.withToken(App.PLATFORM, List.of(row.repositoryId()), Map.of("actions", "read"), token -> {
				GitHubHttp.Answer run = this.apps.http()
					.send("GET", "/repos/" + row.repository() + "/actions/runs/" + row.runId(), token.value(), null);
				return run.ok() && "completed".equals(run.body().path("status").asString(""));
			});
		}
		catch (GitHubException ex) {
			return false;
		}
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Scheduler {

		private final ExchangeRevocations revocations;

		Scheduler(ExchangeRevocations revocations) {
			this.revocations = revocations;
		}

		@Scheduled(fixedDelayString = "${vibefix.github.exchange.revocation-poll:PT30S}",
				initialDelayString = "${vibefix.github.exchange.revocation-poll:PT30S}")
		void run() {
			this.revocations.poll();
		}

	}

}
