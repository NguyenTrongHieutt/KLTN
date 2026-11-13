package vn.vibefix.github.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.ActionsRuns.Correlation;
import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.github.ActionsRuns.RunMarker;
import vn.vibefix.github.ActionsRuns.Status;
import vn.vibefix.github.ExchangeReason;
import vn.vibefix.github.GitHubApps.App;

/**
 * {@code github.exchange_correlation} (AD-44): one row per dispatched run, with its operation, target, dispatching
 * App (and, for the Source App, the Customer's installation, Story 23.6a), grant, status, run and times. It never
 * holds a token or a JWT. The run's page is recorded when the run is found; the conclusion of its first attempt, and
 * its marker, when a read finds that attempt completed. A Source App row names the Customer's Source Repository and
 * links its run, so every row is deleted {@link #purge 30 days after its expiry} once no token is out. The callers hold
 * the transactions.
 */
@Component
class ExchangeCorrelations {

	private static final String COLUMNS = """
			id, operation, target_repository, target_repository_id, target_default_branch, workflow_path, dispatching_app,
			source_installation_id, grant_repository_ids::text AS grant_ids, grant_permissions::text AS permissions,
			confirmed_head_sha, status, refusal_reason, run_id, run_html_url, run_conclusion, run_marker, created_at,
			expires_at, issued_at, revoked_at
			""";

	/**
	 * One row.
	 * @param defaultBranch the target's default branch when the run was dispatched: the run's {@code ref}
	 * @param app the dispatching App, {@code PLATFORM} (the runner) or {@code SOURCE} (a Customer's Source Repository)
	 * @param sourceInstallationId the Customer's Source App installation that dispatched the run, for {@code SOURCE}
	 * only: its token reads the run back (Story 23.6a)
	 * @param permissions exactly the operation's permissions, as the token is minted with them
	 * @param runHtmlUrl the run's page on GitHub, once it is found
	 * @param runConclusion GitHub's conclusion of the run's first attempt, once a read found it completed
	 * @param runMarker what that conclusion alone does not say, recorded with it
	 */
	record Row(UUID id, Operation operation, String repository, long repositoryId, String defaultBranch,
			String workflowPath, String app, @Nullable Long sourceInstallationId, Set<Long> grant,
			Map<String, String> permissions, @Nullable String confirmedHeadSha, Status status,
			@Nullable ExchangeReason refusal, @Nullable Long runId, @Nullable String runHtmlUrl,
			@Nullable String runConclusion, @Nullable RunMarker runMarker, Instant createdAt, Instant expiresAt,
			@Nullable Instant issuedAt, @Nullable Instant revokedAt) {

		Row {
			grant = Set.copyOf(grant);
			permissions = Map.copyOf(permissions);
		}

		Correlation correlation() {
			return new Correlation(this.id, this.operation, this.repository, this.repositoryId, this.workflowPath, this.grant,
					this.confirmedHeadSha, this.status, this.refusal, this.runId, this.createdAt, this.expiresAt, this.issuedAt,
					this.revokedAt, dispatchingApp(), this.runHtmlUrl, this.runConclusion);
		}

		/** The dispatching App, whose token reads the run back. */
		App dispatchingApp() {
			return App.valueOf(this.app);
		}

		/** Whether a run may still exchange under it: open and not past its expiry. */
		boolean open(Instant now) {
			return this.status == Status.OPEN && now.isBefore(this.expiresAt);
		}

	}

	private final JdbcClient jdbc;

	ExchangeCorrelations(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(Row row) {
		this.jdbc.sql("""
				INSERT INTO github.exchange_correlation (id, operation, target_repository, target_repository_id,
				    target_default_branch, workflow_path, dispatching_app, source_installation_id, grant_repository_ids,
				    grant_permissions, confirmed_head_sha, status, created_at, expires_at)
				VALUES (:id, :operation, :repository, :repositoryId, :defaultBranch, :workflowPath, :app,
				    CAST(:installation AS BIGINT), CAST(:grant AS JSONB), CAST(:permissions AS JSONB), :confirmed, 'OPEN',
				    :createdAt, :expiresAt)
				""")
			.param("id", row.id())
			.param("operation", row.operation().code())
			.param("repository", row.repository())
			.param("repositoryId", row.repositoryId())
			.param("defaultBranch", row.defaultBranch())
			.param("workflowPath", row.workflowPath())
			.param("app", row.app())
			.param("installation", row.sourceInstallationId())
			.param("grant", GitHubHttp.JSON.writeValueAsString(new TreeSet<>(row.grant())))
			.param("permissions", GitHubHttp.JSON.writeValueAsString(new TreeMap<>(row.permissions())))
			.param("confirmed", row.confirmedHeadSha())
			.param("createdAt", Timestamp.from(row.createdAt()))
			.param("expiresAt", Timestamp.from(row.expiresAt()))
			.update();
	}

	Optional<Row> find(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM github.exchange_correlation WHERE id = :id")
			.param("id", id)
			.query(ExchangeCorrelations::row)
			.optional();
	}

	/** The row, locked until the caller's transaction ends: one exchange per correlation. */
	Optional<Row> lock(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM github.exchange_correlation WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(ExchangeCorrelations::row)
			.optional();
	}

	/** The run GitHub gave the dispatch, with its page when GitHub showed one, kept until the exchange names it. */
	void runFound(UUID id, long runId, @Nullable String htmlUrl) {
		this.jdbc.sql("""
				UPDATE github.exchange_correlation SET run_id = :runId, run_html_url = CAST(:url AS TEXT)
				WHERE id = :id AND run_id IS NULL
				""")
			.param("id", id)
			.param("runId", runId)
			.param("url", htmlUrl)
			.update();
	}

	/**
	 * What a read of the correlation's run showed (Story 23.6a): its page, and the conclusion of its first attempt with
	 * its marker once that completed. A page not shown keeps the one recorded; a conclusion once recorded is never
	 * replaced; a run other than the correlation's records nothing.
	 */
	void runRead(UUID id, long runId, @Nullable String htmlUrl, @Nullable String conclusion, @Nullable RunMarker marker) {
		this.jdbc.sql("""
				UPDATE github.exchange_correlation SET run_html_url = coalesce(CAST(:url AS TEXT), run_html_url),
				    run_conclusion = CAST(:conclusion AS TEXT),
				    run_marker = CASE WHEN CAST(:conclusion AS TEXT) IS NULL THEN NULL ELSE CAST(:marker AS TEXT) END
				WHERE id = :id AND run_id = :runId AND run_conclusion IS NULL
				""")
			.param("id", id)
			.param("runId", runId)
			.param("url", htmlUrl)
			.param("conclusion", conclusion)
			.param("marker", marker == null ? null : marker.name())
			.update();
	}

	/**
	 * Deletes the correlations whose expiry is {@code cutoff} or earlier, unless their token is still out (open past
	 * expiry, the poll expires them first); returns how many.
	 */
	int purge(Instant cutoff) {
		return this.jdbc.sql("""
				DELETE FROM github.exchange_correlation
				WHERE expires_at <= :cutoff AND status NOT IN ('OPEN', 'EXCHANGED')
				""").param("cutoff", Timestamp.from(cutoff)).update();
	}

	/** @return whether the open correlation was refused now */
	boolean refuse(UUID id, ExchangeReason reason) {
		return this.jdbc.sql("""
				UPDATE github.exchange_correlation SET status = 'REFUSED', refusal_reason = :reason
				WHERE id = :id AND status = 'OPEN'
				""").param("id", id).param("reason", reason.name()).update() == 1;
	}

	/**
	 * Records the run the exchange verified; when it is not the run found before, what was read of that one goes.
	 * @return whether the open correlation is exchanged now
	 */
	boolean exchanged(UUID id, long runId, Instant at) {
		return this.jdbc.sql("""
				UPDATE github.exchange_correlation SET status = 'EXCHANGED', run_id = :runId, issued_at = :at,
				    run_html_url = CASE WHEN run_id IS DISTINCT FROM CAST(:runId AS BIGINT) THEN NULL ELSE run_html_url END,
				    run_conclusion = CASE WHEN run_id IS DISTINCT FROM CAST(:runId AS BIGINT) THEN NULL ELSE run_conclusion END,
				    run_marker = CASE WHEN run_id IS DISTINCT FROM CAST(:runId AS BIGINT) THEN NULL ELSE run_marker END
				WHERE id = :id AND status = 'OPEN'
				""").param("id", id).param("runId", runId).param("at", Timestamp.from(at)).update() == 1;
	}

	/** @return whether the exchanged correlation's token is recorded revoked now */
	boolean revoked(UUID id, Instant at) {
		return this.jdbc.sql("""
				UPDATE github.exchange_correlation SET status = 'REVOKED', revoked_at = :at
				WHERE id = :id AND status = 'EXCHANGED'
				""").param("id", id).param("at", Timestamp.from(at)).update() == 1;
	}

	/** @return whether the exchanged correlation is recorded {@code REVOKE_UNKNOWN} now, which happens once */
	boolean revokeUnknown(UUID id) {
		return this.jdbc.sql("""
				UPDATE github.exchange_correlation SET status = 'REVOKE_UNKNOWN'
				WHERE id = :id AND status = 'EXCHANGED'
				""").param("id", id).update() == 1;
	}

	/** @return whether the open correlation, which its caller gave up on, is {@code EXPIRED} now */
	boolean close(UUID id) {
		return this.jdbc.sql("UPDATE github.exchange_correlation SET status = 'EXPIRED' WHERE id = :id AND status = 'OPEN'")
			.param("id", id)
			.update() == 1;
	}

	/** Open correlations past their expiry become {@code EXPIRED}; returns how many. */
	int expire(Instant now) {
		return this.jdbc.sql("""
				UPDATE github.exchange_correlation SET status = 'EXPIRED'
				WHERE status = 'OPEN' AND expires_at <= :now
				""").param("now", Timestamp.from(now)).update();
	}

	/** Every correlation whose token is out, oldest first. */
	List<Row> exchanged() {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM github.exchange_correlation WHERE status = 'EXCHANGED' ORDER BY issued_at")
			.query(ExchangeCorrelations::row)
			.list();
	}

	private static Row row(ResultSet rs, int rowNumber) throws SQLException {
		Set<Long> grant = new LinkedHashSet<>();
		for (JsonNode id : GitHubHttp.JSON.readTree(rs.getString("grant_ids"))) {
			grant.add(id.asLong());
		}
		Map<String, String> permissions = new LinkedHashMap<>();
		GitHubHttp.JSON.readTree(rs.getString("permissions"))
			.properties()
			.forEach(entry -> permissions.put(entry.getKey(), entry.getValue().asString()));
		String refusal = rs.getString("refusal_reason");
		String marker = rs.getString("run_marker");
		Long runId = rs.getObject("run_id", Long.class);
		return new Row(rs.getObject("id", UUID.class), Operation.of(rs.getString("operation")), rs.getString("target_repository"),
				rs.getLong("target_repository_id"), rs.getString("target_default_branch"), rs.getString("workflow_path"),
				rs.getString("dispatching_app"), rs.getObject("source_installation_id", Long.class), grant, permissions,
				rs.getString("confirmed_head_sha"), Status.valueOf(rs.getString("status")),
				refusal == null ? null : ExchangeReason.valueOf(refusal), runId, rs.getString("run_html_url"),
				rs.getString("run_conclusion"), marker == null ? null : RunMarker.valueOf(marker),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("expires_at").toInstant(), instant(rs, "issued_at"), instant(rs, "revoked_at"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
