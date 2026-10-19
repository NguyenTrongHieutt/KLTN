package vn.vibefix.product.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubRepositories.RepositoryMetadata;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.RateLimitedException;

/**
 * A Customer's Products (FR-7): registration after every repository check and the three
 * acknowledgments, at most 5 a day (AD-34); the list; and one Product's detail.
 */
@Component
class Products {

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	/** The AI tools CUS-3 step 1 offers (FR-7, OQ7). */
	enum Tool {
		LOVABLE, BOLT, REPLIT, V0, BASE44, CURSOR, CLAUDE_CODE, CODEX, OTHER
	}

	/** The scan state; every Product waits for its first scan until Story 2.3. */
	enum ScanState {
		AWAITING_SCAN
	}

	/**
	 * {@code vibefix.product.*}.
	 * @param noticeVersion the version of N-1, N-1b, and N-2 a registration must acknowledge
	 * @param perDay registrations a Customer may make per Vietnam day (AD-34 seed limit)
	 */
	@ConfigurationProperties("vibefix.product")
	record ProductProperties(String noticeVersion, int perDay) {

		ProductProperties {
			if (noticeVersion == null || noticeVersion.isBlank()) {
				noticeVersion = "1.0";
			}
			if (perDay < 1) {
				perDay = 5;
			}
		}

	}

	/** A registration as CUS-3 submits it. */
	record Registration(String repositoryUrl, Set<Tool> tools, String name, String description,
			@Nullable String demoUrl, @Nullable String publicNoticeVersion, @Nullable String copyPermissionVersion,
			@Nullable String detectionNoticeVersion) {
	}

	record Product(UUID id, UUID customerId, String name, String description, @Nullable String demoUrl, List<Tool> tools,
			long repositoryId, String repositoryFullName, String repositoryOwnerType, String defaultBranch,
			@Nullable String forkParent, String noticeVersion, Instant acknowledgedAt, Instant createdAt,
			ScanState scanState) {
	}

	private static final String COLUMNS = """
			id, customer_account_id, name, description, demo_url, ai_tools, repository_github_id, repository_full_name,
			repository_owner_type, repository_default_branch, repository_fork_parent, copy_permission_version,
			acknowledged_at, created_at
			""";

	private final JdbcClient jdbc;

	private final RepositoryChecks checks;

	private final ProductProperties properties;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	Products(JdbcClient jdbc, RepositoryChecks checks, ProductProperties properties, AuditLog auditLog, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.checks = checks;
		this.properties = properties;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Registers the Product after checking the repository again (the wizard's checks are hints).
	 * @throws ApiException {@code VALIDATION NOTICE_REQUIRED}; {@code RATE_LIMITED PRODUCT_LIMIT}; any
	 * repository check's {@code GUARD_FAILED}
	 */
	@Transactional
	UUID register(UUID customerId, Registration registration) {
		String version = this.properties.noticeVersion();
		if (!version.equals(registration.publicNoticeVersion()) || !version.equals(registration.copyPermissionVersion())
				|| !version.equals(registration.detectionNoticeVersion())) {
			throw new ApiException(ErrorCode.VALIDATION, ProductReason.NOTICE_REQUIRED);
		}
		Instant now = this.clock.instant();
		Instant today = LocalDate.ofInstant(now, VIETNAM).atStartOfDay(VIETNAM).toInstant();
		int registered = this.jdbc.sql("""
				SELECT count(*) FROM product.product WHERE customer_account_id = :customer AND created_at >= :today
				""").param("customer", customerId).param("today", Timestamp.from(today)).query(Integer.class).single();
		if (registered >= this.properties.perDay()) {
			throw new RateLimitedException(Duration.between(now, today.atZone(VIETNAM).plusDays(1).toInstant()),
					ProductReason.PRODUCT_LIMIT);
		}
		RepositoryMetadata repository = this.checks.check(customerId, registration.repositoryUrl());
		UUID id = this.ids.newId();
		try {
			this.jdbc.sql("""
					INSERT INTO product.product (id, customer_account_id, name, description, demo_url, ai_tools,
					    repository_github_id, repository_full_name, repository_owner_type, repository_default_branch,
					    repository_fork_parent, public_notice_version, copy_permission_version, detection_notice_version,
					    acknowledged_at, created_at)
					VALUES (:id, :customer, :name, :description, :demo, CAST(:tools AS TEXT[]), :repositoryId, :fullName,
					    :ownerType, :branch, :parent, :version, :version, :version, :now, :now)
					""")
				.param("id", id)
				.param("customer", customerId)
				.param("name", registration.name().strip())
				.param("description", registration.description().strip())
				.param("demo", registration.demoUrl() == null || registration.demoUrl().isBlank() ? null
						: registration.demoUrl().strip())
				.param("tools", "{" + String.join(",", EnumSet.copyOf(registration.tools()).stream().map(Enum::name).toList()) + "}")
				.param("repositoryId", repository.id())
				.param("fullName", repository.fullName())
				.param("ownerType", repository.ownerType())
				.param("branch", repository.defaultBranch())
				.param("parent", repository.forkParent())
				.param("version", version)
				.param("now", Timestamp.from(now))
				.update();
		}
		catch (DuplicateKeyException ex) {
			throw RepositoryChecks.refuse(ProductReason.REPO_ALREADY_LINKED, repository);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(customerId), "product.registered",
				AuditObject.of("product.product", id)));
		return id;
	}

	/** The Customer's live Products, newest first. */
	@Transactional(readOnly = true)
	List<Product> list(UUID customerId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM product.product WHERE customer_account_id = :customer AND deleted_at IS NULL
				ORDER BY created_at DESC, id
				""").param("customer", customerId).query(Products::product).list();
	}

	@Transactional(readOnly = true)
	Optional<Product> find(UUID productId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM product.product WHERE id = :id AND deleted_at IS NULL")
			.param("id", productId)
			.query(Products::product)
			.optional();
	}

	private static Product product(ResultSet rs, int n) throws SQLException {
		Array tools = rs.getArray("ai_tools");
		Timestamp acknowledged = rs.getTimestamp("acknowledged_at");
		return new Product(rs.getObject("id", UUID.class), rs.getObject("customer_account_id", UUID.class),
				rs.getString("name"), rs.getString("description"), rs.getString("demo_url"),
				Arrays.stream((String[]) tools.getArray()).map(Tool::valueOf).toList(), rs.getLong("repository_github_id"),
				rs.getString("repository_full_name"), rs.getString("repository_owner_type"),
				rs.getString("repository_default_branch"), rs.getString("repository_fork_parent"),
				rs.getString("copy_permission_version"), acknowledged.toInstant(), rs.getTimestamp("created_at").toInstant(),
				ScanState.AWAITING_SCAN);
	}

}
