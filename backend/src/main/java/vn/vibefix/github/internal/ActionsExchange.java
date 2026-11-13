package vn.vibefix.github.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.nimbusds.jwt.JWTClaimsSet;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.Status;
import vn.vibefix.github.ExchangeReason;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.InstallationToken;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubExchangeIssued;
import vn.vibefix.github.GitHubExchangeRefused;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The Actions token exchange (AD-44), promoted from Spike S6: a run posts {@code {id_token, correlation}} and
 * gets one token narrowed to its correlation's grant, with exactly its operation's permissions, when every
 * check holds, in this order:
 * <ol>
 * <li>{@code signature}, {@code issuer}, {@code audience}, {@code expiry} ({@link ActionsIdTokens});</li>
 * <li>{@code correlation}: known and open; {@code replay}: not exchanged before;</li>
 * <li>{@code repository} and {@code repository_id} the target's; {@code event} {@code workflow_dispatch};
 * {@code runner} {@code github-hosted};</li>
 * <li>{@code run}: read back with the dispatching App's token, its workflow path, {@code head_sha} equal to
 * {@code workflow_sha}, the dispatching App's bot as triggering actor, and the correlation in its title;</li>
 * <li>{@code blob}: the workflow file at {@code workflow_sha} an accepted blob ({@link AcceptedWorkflows});</li>
 * <li>{@code sha}: for {@code copy} with a confirmed SHA, the run's head is that commit;</li>
 * <li>{@code ref}: the target's default branch.</li>
 * </ol>
 * The dispatching App ({@link DispatchingApps}) is the Platform App for a run on the runner, and the Source App for a
 * run of {@code vibefix.yml} in a Customer's Source Repository (Story 23.6a): its run and workflow file are read back
 * with a token from the Customer's installation the correlation records, narrowed to that repository, and its bot
 * must have triggered the run. When GitHub refuses that installation's read-back token (403, 404, 422: the Customer
 * uninstalled or suspended the App mid-run), the exchange answers as for GitHub's outage. The token issued is minted by
 * the Platform App whatever dispatched the run, since every grant names platform repositories.
 * A refusal before verification (the first four, and an unknown or closed correlation) names nothing
 * reliably: it is only counted and logged by kind. After it, the correlation becomes {@code REFUSED}, except a
 * replay, which leaves it {@code EXCHANGED}, and {@link GitHubExchangeRefused} is published for {@code admin}
 * to audit and alert. GitHub's outage refuses nothing: no answer or a server error to the read-back token, the
 * run or the workflow file, like a mint GitHub refuses, answers 502 and leaves the correlation open. A verified
 * token counts once against its correlation's attempts ({@link ExchangeRateLimiter}). The token issued lives only
 * in {@link IssuedTokens}; nothing here logs the ID token or the token, and no exception message holds either.
 */
@Component
class ActionsExchange {

	private static final Logger log = LoggerFactory.getLogger(ActionsExchange.class);

	private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private static final Pattern RUN_ID = Pattern.compile("[1-9][0-9]{0,18}");

	private static final Set<Status> HELD = Set.of(Status.EXCHANGED, Status.REVOKED, Status.REVOKE_UNKNOWN);

	private final ActionsIdTokens idTokens;

	private final ExchangeCorrelations correlations;

	private final IssuedTokens issued;

	private final ExchangeRateLimiter limiter;

	private final GitHubAppsClient apps;

	private final DispatchingApps dispatching;

	private final AcceptedWorkflows accepted;

	private final ApplicationEventPublisher events;

	private final TransactionTemplate transactions;

	private final IdGenerator ids;

	private final Clock clock;

	private final Map<ExchangeReason, AtomicLong> unverified = new EnumMap<>(ExchangeReason.class);

	ActionsExchange(ActionsIdTokens idTokens, ExchangeCorrelations correlations, IssuedTokens issued,
			ExchangeRateLimiter limiter, GitHubAppsClient apps, DispatchingApps dispatching,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager, IdGenerator ids, Clock clock) {
		this.idTokens = idTokens;
		this.correlations = correlations;
		this.issued = issued;
		this.limiter = limiter;
		this.apps = apps;
		this.dispatching = dispatching;
		this.accepted = new AcceptedWorkflows(apps.properties(), clock);
		this.events = events;
		this.transactions = new TransactionTemplate(transactionManager);
		this.ids = ids;
		this.clock = clock;
		for (ExchangeReason reason : ExchangeReason.values()) {
			if (reason.beforeVerification()) {
				this.unverified.put(reason, new AtomicLong());
			}
		}
	}

	/**
	 * One exchange: the request's body in, the token out.
	 * @param address the caller's address, for the abuse limits
	 * @throws vn.vibefix.shared.RateLimitedException over a limit
	 * @throws ApiException {@code FORBIDDEN} with the {@link ExchangeReason} of the first check that failed
	 * @throws ResponseStatusException 502 when GitHub will not mint the token, or gives no answer or a server error
	 * to a read-back: the correlation stays open and the run may ask again
	 */
	InstallationToken exchange(byte[] body, String address) {
		this.limiter.call(address);
		String idToken;
		String correlationText;
		try {
			JsonNode request = GitHubHttp.JSON.readTree(body);
			if (request == null || !request.isObject() || !request.path("id_token").isString()
					|| !request.path("correlation").isString()) {
				throw unverified(ExchangeReason.SIGNATURE);
			}
			idToken = request.path("id_token").asString();
			correlationText = request.path("correlation").asString();
		}
		catch (ApiException refused) {
			throw refused;
		}
		catch (RuntimeException notJson) {
			throw unverified(ExchangeReason.SIGNATURE);
		}
		ActionsIdTokens.Verified verified = this.idTokens.verify(idToken);
		if (verified.refusal() != null) {
			throw unverified(verified.refusal());
		}
		JWTClaimsSet claims = verified.claims();
		if (!UUID_TEXT.matcher(correlationText).matches()) {
			throw unverified(ExchangeReason.CORRELATION);
		}
		// Only a verified token counts against a correlation, so forged posts naming it cannot lock its run out.
		this.limiter.attempt(correlationText);
		UUID id = UUID.fromString(correlationText);
		ExchangeCorrelations.Row row = this.correlations.find(id).orElseThrow(() -> unverified(ExchangeReason.CORRELATION));
		if (HELD.contains(row.status())) {
			throw refuse(row, ExchangeReason.REPLAY);
		}
		if (!row.open(this.clock.instant())) {
			throw unverified(ExchangeReason.CORRELATION);
		}
		ExchangeReason refusal = check(row, claims);
		if (refusal != null) {
			throw refuse(row, refusal);
		}
		return issue(row, Long.parseLong(ActionsIdTokens.claim(claims, "run_id")));
	}

	/** How many attempts were refused before verification, by reason, since this instance started. */
	Map<ExchangeReason, Long> unverifiedCounts() {
		Map<ExchangeReason, Long> counts = new EnumMap<>(ExchangeReason.class);
		this.unverified.forEach((reason, count) -> counts.put(reason, count.get()));
		return counts;
	}

	/** The checks after verification, in order; the first refusal, or {@code null} when all hold. */
	private @Nullable ExchangeReason check(ExchangeCorrelations.Row row, JWTClaimsSet claims) {
		if (!row.repository().equalsIgnoreCase(ActionsIdTokens.claim(claims, "repository"))
				|| !String.valueOf(row.repositoryId()).equals(ActionsIdTokens.claim(claims, "repository_id"))) {
			return ExchangeReason.REPOSITORY;
		}
		if (!"workflow_dispatch".equals(ActionsIdTokens.claim(claims, "event_name"))) {
			return ExchangeReason.EVENT;
		}
		if (!"github-hosted".equals(ActionsIdTokens.claim(claims, "runner_environment"))) {
			return ExchangeReason.RUNNER;
		}
		String runId = ActionsIdTokens.claim(claims, "run_id");
		String workflowSha = ActionsIdTokens.claim(claims, "workflow_sha");
		if (!RUN_ID.matcher(runId).matches() || !SHA.matcher(workflowSha).matches()) {
			return ExchangeReason.RUN;
		}
		Reads reads;
		try {
			reads = read(row, Long.parseLong(runId), workflowSha);
		}
		catch (GitHubException ex) {
			if (unavailable(ex.status()) || sourceAppGone(row, ex.status())) {
				throw githubUnavailable(row, "mint the read-back token", ex.status());
			}
			log.warn("The Actions exchange could not read run {} of correlation {} back: HTTP {}", runId, row.id(),
					ex.status());
			return ExchangeReason.RUN;
		}
		catch (IllegalArgumentException ex) {
			return ExchangeReason.RUN;
		}
		if (unavailable(reads.run().status())) {
			throw githubUnavailable(row, "read the run", reads.run().status());
		}
		JsonNode run = reads.run().status() == 200 ? reads.run().body() : null;
		if (run == null || !row.workflowPath().equals(run.path("path").asString(""))
				|| !workflowSha.equals(run.path("head_sha").asString(""))
				|| !reads.bot().equalsIgnoreCase(run.path("triggering_actor").path("login").asString(""))
				|| !run.path("display_title").asString("").contains(row.id().toString())) {
			return ExchangeReason.RUN;
		}
		AcceptedWorkflows.Checked blob;
		try {
			blob = this.accepted.check(row.workflowPath(), reads.file());
		}
		catch (GitHubException ex) {
			if (unavailable(ex.status())) {
				throw githubUnavailable(row, "read the workflow file", ex.status());
			}
			return ExchangeReason.BLOB;
		}
		if (!blob.accepted()) {
			return ExchangeReason.BLOB;
		}
		if (row.operation() == Operation.COPY && row.confirmedHeadSha() != null
				&& !row.confirmedHeadSha().equals(run.path("head_sha").asString(""))) {
			return ExchangeReason.SHA;
		}
		if (!("refs/heads/" + row.defaultBranch()).equals(ActionsIdTokens.claim(claims, "ref"))) {
			return ExchangeReason.REF;
		}
		return null;
	}

	/**
	 * GitHub's answers for the run, and for its workflow file at {@code workflow_sha}, read with the dispatching App,
	 * and that App's bot.
	 */
	private record Reads(GitHubHttp.Answer run, GitHubHttp.Answer file, String bot) {
	}

	/** No answer, or a server error: GitHub's outage, which refuses nothing. */
	private static boolean unavailable(int status) {
		return status == 0 || status >= 500;
	}

	/**
	 * A Source App read-back token GitHub refuses as it refuses an installation the Customer uninstalled or suspended
	 * mid-run (403, 404, 422): no fault of the run, so it is answered as GitHub's outage and refuses nothing (Story
	 * 23.6a).
	 */
	private static boolean sourceAppGone(ExchangeCorrelations.Row row, int status) {
		return App.SOURCE.name().equals(row.app()) && (status == 403 || status == 404 || status == 422);
	}

	/**
	 * GitHub could not answer a read-back: 502, as for a mint GitHub refuses. The correlation stays open, nothing is
	 * published or counted, and the run may ask again.
	 */
	private static ResponseStatusException githubUnavailable(ExchangeCorrelations.Row row, String what, int status) {
		log.warn("The Actions exchange could not {} for correlation {}: HTTP {}; the run may ask again", what, row.id(), status);
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY);
	}

	/**
	 * The run and its workflow file read back with a token of the dispatching App narrowed to the target: the Source
	 * App's from the Customer's installation the correlation records, else the Platform App's.
	 */
	private Reads read(ExchangeCorrelations.Row row, long runId, String workflowSha) {
		App app = dispatchingApp(row);
		String bot = this.dispatching.botLogin(app);
		return this.dispatching.withToken(app, row.sourceInstallationId(), row.repositoryId(),
				Map.of("actions", "read", "contents", "read"), token -> {
					GitHubHttp.Answer run = this.apps.http()
						.send("GET", "/repos/" + row.repository() + "/actions/runs/" + runId, token.value(), null);
					GitHubHttp.Answer file = this.apps.http()
						.send("GET", "/repos/" + row.repository() + "/contents/" + row.workflowPath() + "?ref=" + workflowSha,
								token.value(), null);
					return new Reads(run, file, bot);
				});
	}

	/**
	 * The token, once the correlation is locked and still open: minted by the Platform App, whatever dispatched the
	 * run, since the grant names platform repositories only, narrowed to the grant with exactly the operation's
	 * permissions, kept in memory, the row {@code EXCHANGED}, and {@link GitHubExchangeIssued} published, all in one
	 * transaction. A token minted for a transaction that then fails is revoked at once.
	 */
	private InstallationToken issue(ExchangeCorrelations.Row checked, long runId) {
		InstallationToken[] minted = { null };
		ExchangeReason late;
		try {
			late = this.transactions.execute(status -> {
				ExchangeCorrelations.Row row = this.correlations.lock(checked.id()).orElseThrow();
				if (HELD.contains(row.status())) {
					return ExchangeReason.REPLAY;
				}
				Instant now = this.clock.instant();
				if (!row.open(now)) {
					return ExchangeReason.CORRELATION;
				}
				// The Platform App's, never the Source App's: every grant names platform repositories (Story 23.6a).
				InstallationToken token = this.apps.token(App.PLATFORM, row.grant(), row.operation().permissions());
				minted[0] = token;
				this.issued.put(row.id(), token);
				this.correlations.exchanged(row.id(), runId, now);
				this.events.publishEvent(new GitHubExchangeIssued(this.ids.newId(), row.id(), row.operation(), runId));
				return null;
			});
		}
		catch (GitHubException refused) {
			forget(checked.id(), minted[0]);
			log.warn("GitHub would not mint the token of correlation {}: HTTP {}", checked.id(), refused.status());
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
		}
		catch (RuntimeException failed) {
			forget(checked.id(), minted[0]);
			throw failed;
		}
		if (late == ExchangeReason.REPLAY) {
			throw refuse(checked, ExchangeReason.REPLAY);
		}
		if (late != null) {
			throw unverified(late);
		}
		log.info("The Actions exchange issued correlation {} a token for run {}", checked.id(), runId);
		return minted[0];
	}

	/** A token whose transaction failed never leaves the exchange: it is revoked at once. */
	private void forget(UUID correlation, @Nullable InstallationToken token) {
		if (token == null) {
			return;
		}
		this.issued.remove(correlation);
		try {
			this.apps.revoke(token);
		}
		catch (RuntimeException ignored) {
			// GitHub expires it within the hour.
		}
	}

	/** A refusal before verification: counted and logged by kind only. */
	private ApiException unverified(ExchangeReason reason) {
		this.unverified.get(reason).incrementAndGet();
		log.info("The Actions exchange refused an ID token before verification: {}", reason.code());
		return new ApiException(ErrorCode.FORBIDDEN, reason);
	}

	/** A refusal after verification: the open correlation becomes REFUSED (a replay leaves it), audited and alerted. */
	private ApiException refuse(ExchangeCorrelations.Row row, ExchangeReason reason) {
		log.warn("The Actions exchange refused correlation {}: {}", row.id(), reason.code());
		this.transactions.executeWithoutResult(status -> {
			if (reason != ExchangeReason.REPLAY) {
				this.correlations.refuse(row.id(), reason);
			}
			this.events.publishEvent(new GitHubExchangeRefused(this.ids.newId(), row.id(), row.operation(), reason));
		});
		return new ApiException(ErrorCode.FORBIDDEN, reason);
	}

	/**
	 * The App that dispatched the run, whose token reads it back: the Platform App or the Source App.
	 * @throws IllegalArgumentException for any other, which the check refuses {@code run}
	 */
	private static App dispatchingApp(ExchangeCorrelations.Row row) {
		App app = row.dispatchingApp();
		if (app != App.PLATFORM && app != App.SOURCE) {
			throw new IllegalArgumentException("The " + app + " App dispatches no Actions run");
		}
		return app;
	}

}
