package vn.vibefix.scanning.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.scanning.Finding;
import vn.vibefix.scanning.FindingCategory;
import vn.vibefix.scanning.FindingResolution;
import vn.vibefix.scanning.FindingStatus;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanStatus;
import vn.vibefix.scanning.ScanSummary;
import vn.vibefix.scanning.ScanSummary.UnscannedFile;
import vn.vibefix.scanning.ScanTarget;
import vn.vibefix.scanning.Scans;
import vn.vibefix.scanning.ShaRange;
import vn.vibefix.shared.IdGenerator;

/** {@link Scans} on the {@code scanning} schema. */
@Component
class JdbcScans implements Scans {

	/** The External Operation that runs one scan run, keyed by the run's ID. */
	static final String OPERATION_TYPE = "scanning.scan";

	/** AD-6 seed lanes: push scans (0, Epic 7) go before intake scans. */
	static final int INTAKE_PRIORITY = 1;

	static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SUMMARY_COLUMNS = """
			id, product_id, purpose, status, requested_at, finished_at, found_count, unscanned_count, unscanned::text
			""";

	private static final String FINDING_COLUMNS = """
			id, category, rule_id, rule_type, file_path, commit_sha, committed_at, masked_preview, match_count, status,
			created_at, resolution, resolved_at
			""";

	private final JdbcClient jdbc;

	private final ExternalOperations operations;

	private final FindingResolutions resolutions;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcScans(JdbcClient jdbc, ExternalOperations operations, FindingResolutions resolutions, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.resolutions = resolutions;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID requestScan(ScanTarget target, ShaRange shaRange, ScanPurpose purpose) {
		if (!shaRange.isWholeHistory()) {
			throw new UnsupportedOperationException("Range scans arrive with the push scan (Epic 7)");
		}
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, base_sha, head_sha, status, requested_at)
				VALUES (:id, :product, :customer, :name, :repository, :githubId, :purpose, :base, :head, 'PENDING', :now)
				""")
			.param("id", id)
			.param("product", target.productId())
			.param("customer", target.customerId())
			.param("name", target.productName())
			.param("repository", target.repositoryFullName())
			.param("githubId", target.repositoryGithubId())
			.param("purpose", purpose.name())
			.param("base", shaRange.base())
			.param("head", shaRange.head())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.operations.request(new OperationRequest(OPERATION_TYPE, id, Map.of("scanRunId", id.toString()), null, null,
				INTAKE_PRIORITY));
		return id;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ScanSummary> latest(UUID productId) {
		return this.jdbc.sql("SELECT " + SUMMARY_COLUMNS + """
				 FROM scanning.scan_run WHERE product_id = :product AND (purpose <> 'CLEAN_REPOSITORY' OR adopted)
				ORDER BY requested_at DESC, id DESC LIMIT 1
				""").param("product", productId).query(JdbcScans::summary).optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, ScanSummary> latest(Collection<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, ScanSummary> latest = new LinkedHashMap<>();
		this.jdbc.sql("SELECT DISTINCT ON (product_id) " + SUMMARY_COLUMNS + """
				 FROM scanning.scan_run
				WHERE product_id IN (:products) AND (purpose <> 'CLEAN_REPOSITORY' OR adopted)
				ORDER BY product_id, requested_at DESC, id DESC
				""").param("products", List.copyOf(productIds)).query((rs, n) -> {
			latest.put(rs.getObject("product_id", UUID.class), summary(rs, n));
			return null;
		}).list();
		return latest;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Finding> openFindings(UUID productId) {
		return this.jdbc.sql("SELECT " + FINDING_COLUMNS + """
				 FROM scanning.finding WHERE product_id = :product AND status = 'OPEN'
				ORDER BY committed_at DESC, id
				""").param("product", productId).query(JdbcScans::finding).list();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Integer> openFindingCounts(Collection<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Integer> counts = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT product_id, count(*) AS open FROM scanning.finding
				WHERE product_id IN (:products) AND status = 'OPEN' GROUP BY product_id
				""").param("products", List.copyOf(productIds)).query((rs, n) -> {
			counts.put(rs.getObject("product_id", UUID.class), rs.getInt("open"));
			return null;
		}).list();
		return counts;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Finding> finding(UUID productId, UUID findingId) {
		return this.jdbc.sql("SELECT " + FINDING_COLUMNS + " FROM scanning.finding WHERE id = :id AND product_id = :product")
			.param("id", findingId)
			.param("product", productId)
			.query(JdbcScans::finding)
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean mayResolve(UUID findingId, UUID accountId) {
		return this.resolutions.mayResolve(findingId, accountId);
	}

	@Override
	@Transactional
	public Finding confirmRotation(UUID productId, UUID findingId, UUID accountId) {
		this.resolutions.confirmRotation(productId, findingId, accountId);
		return finding(productId, findingId).orElseThrow();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ScanSummary> run(UUID scanRunId) {
		return this.jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM scanning.scan_run WHERE id = :id")
			.param("id", scanRunId)
			.query(JdbcScans::summary)
			.optional();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void resolveByCleanRepository(UUID productId, UUID accountId, UUID scanRunId) {
		this.resolutions.clearPersonalData(productId, accountId);
		this.jdbc.sql("UPDATE scanning.scan_run SET adopted = true WHERE id = :id AND product_id = :product")
			.param("id", scanRunId)
			.param("product", productId)
			.update();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void setResolvers(UUID findingId, Set<UUID> accountIds) {
		this.resolutions.setResolvers(findingId, accountIds);
	}

	private static ScanSummary summary(ResultSet rs, int n) throws SQLException {
		Timestamp finished = rs.getTimestamp("finished_at");
		int found = rs.getInt("found_count");
		Integer foundCount = rs.wasNull() ? null : found;
		return new ScanSummary(rs.getObject("id", UUID.class), ScanPurpose.valueOf(rs.getString("purpose")),
				ScanStatus.valueOf(rs.getString("status")), rs.getTimestamp("requested_at").toInstant(),
				finished == null ? null : finished.toInstant(), foundCount, rs.getInt("unscanned_count"),
				unscanned(rs.getString("unscanned")));
	}

	static List<UnscannedFile> unscanned(String json) {
		List<UnscannedFile> files = new ArrayList<>();
		for (JsonNode file : JSON.readTree(json)) {
			String reason = file.path("reason").isString() ? file.path("reason").stringValue() : HistoryScanner.TOO_LARGE;
			files.add(new UnscannedFile(file.path("path").stringValue(), file.path("sizeBytes").longValue(), reason));
		}
		return files;
	}

	private static Finding finding(ResultSet rs, int n) throws SQLException {
		int matches = rs.getInt("match_count");
		Integer matchCount = rs.wasNull() ? null : matches;
		return new Finding(rs.getObject("id", UUID.class), FindingCategory.valueOf(rs.getString("category")),
				rs.getString("rule_id"), rs.getString("rule_type"), rs.getString("file_path"), rs.getString("commit_sha"),
				instant(rs, "committed_at"), rs.getString("masked_preview"), matchCount,
				FindingStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
				rs.getString("resolution") == null ? null : FindingResolution.valueOf(rs.getString("resolution")),
				rs.getTimestamp("resolved_at") == null ? null : instant(rs, "resolved_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getTimestamp(column).toInstant();
	}

}
