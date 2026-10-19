package vn.vibefix.product.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.product.internal.Products.Product;
import vn.vibefix.scanning.FindingCategory;
import vn.vibefix.scanning.ScanCompleted;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanTarget;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * A clean repository in place of the Source Repository (FR-65, CUS-5 "Đăng ký kho mã sạch"). The
 * Customer's link passes CUS-3's checks again, then {@code scanning} scans it in full; only a scan
 * without personal data switches the Product to the new repository's ID, in one transaction with its
 * audit entry and the resolution of the Product's personal-data Findings. The previous repository
 * stays on the accepted replacement, so the Product's history keeps every link it had.
 */
@Component
class CleanRepositories {

	enum Status {
		PENDING, ACCEPTED, REJECTED, SUPERSEDED
	}

	/** Why a replacement was refused after its scan. */
	static final String PERSONAL_DATA_FOUND = "PERSONAL_DATA_FOUND";

	record Replacement(UUID id, UUID productId, String repositoryFullName, Status status, @Nullable String reason,
			UUID scanRunId, Instant requestedAt, @Nullable Instant decidedAt) {
	}

	private static final String COLUMNS = """
			id, product_id, repository_full_name, status, reason, scan_run_id, requested_at, decided_at
			""";

	private final JdbcClient jdbc;

	private final Products products;

	private final RepositoryChecks checks;

	private final Scans scans;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	CleanRepositories(JdbcClient jdbc, Products products, RepositoryChecks checks, Scans scans, AuditLog auditLog,
			IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.products = products;
		this.checks = checks;
		this.scans = scans;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Records the Customer's clean repository and requests its scan.
	 * @throws ApiException {@code NOT_FOUND} for a Product that is not the Customer's;
	 * {@code GUARD_FAILED NO_PERSONAL_DATA_FINDING} without an open personal-data Finding; any CUS-3
	 * check's {@code GUARD_FAILED}
	 */
	@Transactional
	Replacement request(UUID customerId, UUID productId, String url) {
		Product product = this.products.find(productId)
			.filter(found -> found.customerId().equals(customerId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		boolean personalData = this.scans.openFindings(productId)
			.stream()
			.anyMatch(finding -> finding.category() == FindingCategory.PERSONAL_DATA);
		if (!personalData) {
			throw new ApiException(ErrorCode.GUARD_FAILED, ProductReason.NO_PERSONAL_DATA_FINDING);
		}
		RepositoryMetadata repository = this.checks.check(customerId, url);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				UPDATE product.repository_replacement SET status = 'SUPERSEDED', decided_at = :now
				WHERE product_id = :product AND status = 'PENDING'
				""").param("product", productId).param("now", Timestamp.from(now)).update();
		UUID run = this.scans.requestScan(new ScanTarget(productId, customerId, product.name(), repository.fullName(),
				repository.id()), vn.vibefix.scanning.ShaRange.wholeHistory(), ScanPurpose.CLEAN_REPOSITORY);
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO product.repository_replacement (id, product_id, requested_by_account_id, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, repository_fork_parent,
				    status, scan_run_id, requested_at)
				VALUES (:id, :product, :customer, :githubId, :fullName, :ownerType, :branch, :parent, 'PENDING', :run, :now)
				""")
			.param("id", id)
			.param("product", productId)
			.param("customer", customerId)
			.param("githubId", repository.id())
			.param("fullName", repository.fullName())
			.param("ownerType", repository.ownerType())
			.param("branch", repository.defaultBranch())
			.param("parent", repository.forkParent())
			.param("run", run)
			.param("now", Timestamp.from(now))
			.update();
		return latest(productId).orElseThrow();
	}

	/** The Product's latest replacement, whatever became of it. */
	@Transactional(readOnly = true)
	Optional<Replacement> latest(UUID productId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM product.repository_replacement WHERE product_id = :product ORDER BY requested_at DESC, id DESC LIMIT 1
				""").param("product", productId).query(CleanRepositories::replacement).optional();
	}

	/** A candidate's scan completed: accept it without personal data, refuse it with. */
	@ApplicationModuleListener
	void on(ScanCompleted completed) {
		decide(completed);
	}

	void decide(ScanCompleted completed) {
		record Pending(UUID id, UUID productId, UUID requester, long githubId, String fullName, String ownerType,
				String branch, @Nullable String parent) {
		}
		Optional<Pending> found = this.jdbc.sql("""
				SELECT id, product_id, requested_by_account_id, repository_github_id, repository_full_name,
				       repository_owner_type, repository_default_branch, repository_fork_parent
				FROM product.repository_replacement WHERE scan_run_id = :run AND status = 'PENDING' FOR UPDATE
				""")
			.param("run", completed.scanRunId())
			.query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
					rs.getObject("requested_by_account_id", UUID.class), rs.getLong("repository_github_id"),
					rs.getString("repository_full_name"), rs.getString("repository_owner_type"),
					rs.getString("repository_default_branch"), rs.getString("repository_fork_parent")))
			.optional();
		if (found.isEmpty()) {
			return;
		}
		Pending pending = found.get();
		Instant now = this.clock.instant();
		if (completed.personalDataFound()) {
			reject(pending.id(), PERSONAL_DATA_FOUND, now);
			return;
		}
		boolean linked = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product.product
				               WHERE repository_github_id = :githubId AND deleted_at IS NULL AND id <> :product)
				""").param("githubId", pending.githubId()).param("product", pending.productId()).query(Boolean.class).single();
		if (linked) {
			reject(pending.id(), ProductReason.REPO_ALREADY_LINKED.name(), now);
			return;
		}
		record Previous(long githubId, String fullName) {
		}
		Previous previous = this.jdbc.sql("""
				SELECT repository_github_id, repository_full_name FROM product.product WHERE id = :id FOR UPDATE
				""")
			.param("id", pending.productId())
			.query((rs, n) -> new Previous(rs.getLong("repository_github_id"), rs.getString("repository_full_name")))
			.single();
		this.jdbc.sql("""
				UPDATE product.product
				SET repository_github_id = :githubId, repository_full_name = :fullName, repository_owner_type = :ownerType,
				    repository_default_branch = :branch, repository_fork_parent = :parent
				WHERE id = :id
				""")
			.param("id", pending.productId())
			.param("githubId", pending.githubId())
			.param("fullName", pending.fullName())
			.param("ownerType", pending.ownerType())
			.param("branch", pending.branch())
			.param("parent", pending.parent())
			.update();
		this.jdbc.sql("""
				UPDATE product.repository_replacement
				SET status = 'ACCEPTED', previous_github_id = :previousId, previous_full_name = :previousName,
				    decided_at = :now
				WHERE id = :id
				""")
			.param("id", pending.id())
			.param("previousId", previous.githubId())
			.param("previousName", previous.fullName())
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(pending.requester()), "product.repository_replaced",
				AuditObject.of("product.product", pending.productId()),
				AuditObject.of("product.repository_replacement", pending.id())));
		this.scans.resolveByCleanRepository(pending.productId(), pending.requester(), completed.scanRunId());
	}

	private void reject(UUID id, String reason, Instant now) {
		this.jdbc.sql("""
				UPDATE product.repository_replacement SET status = 'REJECTED', reason = :reason, decided_at = :now
				WHERE id = :id
				""").param("id", id).param("reason", reason).param("now", Timestamp.from(now)).update();
	}

	private static Replacement replacement(ResultSet rs, int n) throws SQLException {
		Timestamp decided = rs.getTimestamp("decided_at");
		return new Replacement(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
				rs.getString("repository_full_name"), Status.valueOf(rs.getString("status")), rs.getString("reason"),
				rs.getObject("scan_run_id", UUID.class), rs.getTimestamp("requested_at").toInstant(),
				decided == null ? null : decided.toInstant());
	}

}
