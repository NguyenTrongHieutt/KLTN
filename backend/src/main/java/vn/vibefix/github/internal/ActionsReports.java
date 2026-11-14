package vn.vibefix.github.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.nimbusds.jwt.JWTClaimsSet;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.Report;
import vn.vibefix.github.ActionsRuns.Status;
import vn.vibefix.github.ExchangeReason;
import vn.vibefix.github.GitHubExchangeRefused;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * The path report of a {@code handover-copy} run (AD-11, AD-44, FR-32, Story 23.8a), the channel that brings
 * the handover's changed paths to vibefix without a file in a platform repository and without a log line: steps
 * are {@code run:} only, so artifacts are out, job summaries and dispatch outputs cannot be read through the API,
 * and paths echoed into a log could carry workflow commands.
 * <p>
 * The run posts {@code {id_token, correlation, workflows_changed, paths, count}} between its fetch and its push,
 * with a fresh ID token and the same correlation. The checks run in the exchange's order:
 * <ol>
 * <li>{@code report}: the body's shape, which names the ID token;</li>
 * <li>{@code signature}, {@code issuer}, {@code audience}, {@code expiry} ({@link ActionsIdTokens});</li>
 * <li>{@code correlation}: known, {@link Status#EXCHANGED}, and a {@link Operation#HANDOVER_COPY};</li>
 * <li>{@code replay}: no report for it yet;</li>
 * <li>{@code repository}: the claim names the correlation's target, the runner;</li>
 * <li>{@code run}: the claim names the run the exchange recorded on the correlation;</li>
 * <li>{@code report} again: the gzipped base64 list, and a {@code count} it agrees with. It is unpacked only here,
 * so no unverified caller makes vibefix unzip anything.</li>
 * </ol>
 * A refusal is counted by kind and logged by kind, never with a path or a token; the report itself is never
 * logged, and no exception message holds any of it. From {@link ExchangeReason#REPLAY} on, the token is GitHub's
 * and names an exchanged correlation, so {@link GitHubExchangeRefused} is published too, for {@code admin} to
 * audit and alert. The abuse limits are the exchange's (AD-34) on counters of this endpoint's own.
 */
@Component
class ActionsReports {

	private static final Logger log = LoggerFactory.getLogger(ActionsReports.class);

	/** AD-11 [ASSUMPTION]: at most this many paths in one handover's report. */
	static final int MAX_PATHS = 100_000;

	/**
	 * How many bytes the list may hold once unzipped, so no body can expand without bound: well above
	 * {@value #MAX_PATHS} paths of an ordinary length, and far below what a 2 MB body of nothing but zeroes would
	 * become {@code [ASSUMPTION]}.
	 */
	static final int MAX_PATH_BYTES = 32 * 1024 * 1024;

	private static final Pattern UUID_TEXT = Pattern
		.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private static final Pattern RUN_ID = Pattern.compile("[1-9][0-9]{0,18}");

	private final ActionsIdTokens idTokens;

	private final ExchangeCorrelations correlations;

	private final ExchangeReports reports;

	private final ExchangeRateLimiter limiter;

	private final TransactionTemplate transactions;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	private final Map<ExchangeReason, AtomicLong> refusals = new EnumMap<>(ExchangeReason.class);

	ActionsReports(ActionsIdTokens idTokens, ExchangeCorrelations correlations, ExchangeReports reports,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager, IdGenerator ids,
			Clock clock) {
		this.idTokens = idTokens;
		this.correlations = correlations;
		this.reports = reports;
		this.events = events;
		this.ids = ids;
		// Counters of its own: one run makes one exchange and one report, which never use up each other's budget.
		this.limiter = new ExchangeRateLimiter(clock);
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
		for (ExchangeReason reason : ExchangeReason.values()) {
			this.refusals.put(reason, new AtomicLong());
		}
	}

	/**
	 * One report: the request's body in, nothing out.
	 * @param address the caller's address, for the abuse limits
	 * @throws vn.vibefix.shared.RateLimitedException over a limit
	 * @throws ApiException {@code FORBIDDEN} with the {@link ExchangeReason} of the first check that failed
	 */
	void report(byte[] body, String address) {
		this.limiter.call(address);
		Posted posted = shape(body);
		ActionsIdTokens.Verified verified = this.idTokens.verify(posted.idToken());
		if (verified.refusal() != null) {
			throw refuse(verified.refusal());
		}
		JWTClaimsSet claims = verified.claims();
		// Only a verified token counts against a correlation, so forged posts naming it cannot lock its run out.
		this.limiter.attempt(posted.correlation().toString());
		ExchangeCorrelations.Row row = this.correlations.find(posted.correlation())
			.orElseThrow(() -> refuse(ExchangeReason.CORRELATION));
		if (row.status() != Status.EXCHANGED || row.operation() != Operation.HANDOVER_COPY) {
			throw refuse(ExchangeReason.CORRELATION);
		}
		if (this.reports.exists(row.id())) {
			throw refuse(row, ExchangeReason.REPLAY);
		}
		if (!row.repository().equalsIgnoreCase(ActionsIdTokens.claim(claims, "repository"))
				|| !String.valueOf(row.repositoryId()).equals(ActionsIdTokens.claim(claims, "repository_id"))) {
			throw refuse(row, ExchangeReason.REPOSITORY);
		}
		String runId = ActionsIdTokens.claim(claims, "run_id");
		if (!RUN_ID.matcher(runId).matches() || row.runId() == null || row.runId() != Long.parseLong(runId)) {
			throw refuse(row, ExchangeReason.RUN);
		}
		// The list is unpacked only once the run is known: no unverified caller makes vibefix unzip anything.
		byte[] gzip = list(posted);
		boolean stored = Boolean.TRUE.equals(this.transactions.execute(status -> this.reports
			.insert(new ExchangeReports.Row(row.id(), posted.workflowsChanged(), gzip, posted.count(),
					this.clock.instant()))));
		if (!stored) {
			// Two posts of one run raced: the first is the report, the second a replay.
			throw refuse(row, ExchangeReason.REPLAY);
		}
		log.info("Correlation {} reported {} changed path(s); .github/workflows changed: {}", row.id(), posted.count(),
				posted.workflowsChanged());
	}

	/**
	 * The correlation's report, read back for the operation that waits for it: empty for stored bytes that are no
	 * longer the list the run counted, which the operation treats as an unreadable list (FR-32).
	 */
	Optional<Report> find(UUID correlation) {
		return this.reports.find(correlation).map(row -> {
			List<String> paths = paths(row.pathsGzip(), row.count());
			return paths == null ? null
					: new Report(row.correlationId(), row.workflowsChanged(), paths, row.count(), row.reportedAt());
		});
	}

	/** How many reports were refused, by reason, since this instance started. */
	Map<ExchangeReason, Long> refusedCounts() {
		Map<ExchangeReason, Long> counts = new EnumMap<>(ExchangeReason.class);
		this.refusals.forEach((reason, count) -> counts.put(reason, count.get()));
		return counts;
	}

	/** A body whose fields are there: its list is still only base64 text. Nothing here is ever logged. */
	private record Posted(String idToken, UUID correlation, boolean workflowsChanged, String paths, int count) {
	}

	/**
	 * The body's shape, before the ID token is verified: the five fields, a correlation that is a UUID, and a count
	 * within the cap. Everything but the correlation is {@link ExchangeReason#REPORT}; a correlation that is no UUID
	 * is {@code CORRELATION}, as the exchange answers it.
	 */
	private Posted shape(byte[] body) {
		JsonNode request;
		try {
			request = GitHubHttp.JSON.readTree(body);
		}
		catch (RuntimeException notJson) {
			throw refuse(ExchangeReason.REPORT);
		}
		if (request == null || !request.isObject() || !request.path("id_token").isString()
				|| !request.path("correlation").isString() || !request.path("workflows_changed").isBoolean()
				|| !request.path("paths").isString() || !request.path("count").isIntegralNumber()) {
			throw refuse(ExchangeReason.REPORT);
		}
		String correlationText = request.path("correlation").asString();
		if (!UUID_TEXT.matcher(correlationText).matches()) {
			throw refuse(ExchangeReason.CORRELATION);
		}
		long count = request.path("count").asLong();
		if (count < 0 || count > MAX_PATHS) {
			throw refuse(ExchangeReason.REPORT);
		}
		return new Posted(request.path("id_token").asString(), UUID.fromString(correlationText),
				request.path("workflows_changed").asBoolean(), request.path("paths").asString(), (int) count);
	}

	/**
	 * The list as the run wrote it, kept as its own gzipped bytes: base64 that decodes, gzip that unpacks within
	 * {@link #MAX_PATH_BYTES}, and exactly {@code count} paths. Only the separators are read here, so a post never
	 * builds up to {@link #MAX_PATHS} strings that nothing keeps.
	 * @throws ApiException {@link ExchangeReason#REPORT} for anything else
	 */
	private byte[] list(Posted posted) {
		byte[] gzip;
		try {
			gzip = Base64.getDecoder().decode(posted.paths());
		}
		catch (IllegalArgumentException notBase64) {
			throw refuse(ExchangeReason.REPORT);
		}
		if (countPaths(gzip) != posted.count()) {
			throw refuse(ExchangeReason.REPORT);
		}
		return gzip;
	}

	/**
	 * The stored bytes read back as paths.
	 * @return the paths, or {@code null} for bytes that are no longer a readable list of {@code count} paths, so
	 * that no handover is ever recorded from a part of a range (FR-32)
	 */
	private static @Nullable List<String> paths(byte[] gzip, int count) {
		byte[] plain = plain(gzip);
		if (plain == null) {
			return null;
		}
		List<String> paths = new ArrayList<>();
		int start = 0;
		for (int at = 0; at < plain.length; at++) {
			if (plain[at] != 0) {
				continue;
			}
			if (at == start) {
				// An empty path: a leading or doubled NUL, which git never writes.
				return null;
			}
			paths.add(new String(plain, start, at - start, StandardCharsets.UTF_8));
			start = at + 1;
		}
		if (start < plain.length) {
			paths.add(new String(plain, start, plain.length - start, StandardCharsets.UTF_8));
		}
		return paths.size() == count ? paths : null;
	}

	/**
	 * How many paths the gzipped bytes hold, counted from the NUL separators alone.
	 * @return the count, or {@code -1} for bytes that are no such list: not gzip, past
	 * {@link #MAX_PATH_BYTES} unzipped, past {@link #MAX_PATHS} paths, or holding an empty path (a leading or
	 * doubled NUL, which git never writes)
	 */
	private static int countPaths(byte[] gzip) {
		byte[] plain = plain(gzip);
		if (plain == null) {
			return -1;
		}
		int count = 0;
		int start = 0;
		for (int at = 0; at < plain.length; at++) {
			if (plain[at] != 0) {
				continue;
			}
			if (at == start) {
				return -1;
			}
			start = at + 1;
			if (++count > MAX_PATHS) {
				return -1;
			}
		}
		// A trailing NUL terminates the last path, as git diff -z writes it; an unterminated tail is one more.
		return start < plain.length ? count + 1 : count;
	}

	/** The gzipped bytes unpacked, at most {@link #MAX_PATH_BYTES}; {@code null} for anything else. */
	private static byte @Nullable [] plain(byte[] gzip) {
		byte[] plain;
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
			plain = in.readNBytes(MAX_PATH_BYTES + 1);
		}
		catch (IOException | RuntimeException notGzip) {
			return null;
		}
		return plain.length > MAX_PATH_BYTES ? null : plain;
	}

	/**
	 * A refusal before the correlation is known: counted and logged by kind only, since a forged body names nothing
	 * reliably.
	 */
	private ApiException refuse(ExchangeReason reason) {
		this.refusals.get(reason).incrementAndGet();
		log.info("The Actions path report was refused: {}", reason.code());
		return new ApiException(ErrorCode.FORBIDDEN, reason);
	}

	/**
	 * A refusal of a verified token naming an exchanged correlation ({@link ExchangeReason#REPLAY} on): counted and
	 * logged, and published for {@code admin} to audit and alert, as the exchange's own refusal is. The correlation
	 * keeps its status, since its token is already out.
	 */
	private ApiException refuse(ExchangeCorrelations.Row row, ExchangeReason reason) {
		this.refusals.get(reason).incrementAndGet();
		log.warn("The Actions path report of correlation {} was refused: {}", row.id(), reason.code());
		this.transactions.executeWithoutResult(status -> this.events
			.publishEvent(new GitHubExchangeRefused(this.ids.newId(), row.id(), row.operation(), reason)));
		return new ApiException(ErrorCode.FORBIDDEN, reason);
	}

}
