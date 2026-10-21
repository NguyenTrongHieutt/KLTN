package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.files.StoredFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;

/**
 * The platform repositories' deletion (FR-21, FR-38, NFR-10). Thirty days after the Job is Closed or
 * Cancelled, {@value #DELETE} stores the final ZIP of the Intermediary Repository's default branch,
 * owned by the Customer with purpose {@value #ZIP_PURPOSE}, and only then deletes both repositories and
 * reads them back as gone. Deleting the Product requests the same deletion at once, without a ZIP; its
 * payload names the repositories, so the rows may go first.
 */
@Component
class RepositoryDeletions {

	static final String DELETE = "repo.repository_delete";

	static final String ZIP_PURPOSE = "repo.final_zip";

	/** A Setup whose repositories are not deleted yet. */
	record Pending(UUID setupId, UUID jobId, UUID productId, @Nullable PlatformRepository intermediary,
			@Nullable PlatformRepository working, @Nullable String branch, @Nullable UUID archiveFileId) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, intermediary_github_id, intermediary_name, working_github_id, working_name,
			default_branch, archive_file_id
			""";

	private static final String PENDING = " FROM repo.job_setup WHERE deleted_at IS NULL"
			+ " AND (intermediary_github_id IS NOT NULL OR working_github_id IS NOT NULL)";

	private final JdbcClient jdbc;

	private final ExternalOperations operations;

	private final StoredFiles files;

	private final AuditLog auditLog;

	private final Clock clock;

	RepositoryDeletions(JdbcClient jdbc, ExternalOperations operations, StoredFiles files, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.files = files;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** The Job's Setups whose repositories still exist. */
	List<Pending> pending(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + PENDING + " AND job_id = :job ORDER BY started_at, id")
			.param("job", jobId)
			.query(RepositoryDeletions::pending)
			.list();
	}

	/** The Product's Setups whose repositories still exist. */
	List<Pending> ofProduct(UUID productId) {
		return this.jdbc.sql("SELECT " + COLUMNS + PENDING + " AND product_id = :product ORDER BY started_at, id")
			.param("product", productId)
			.query(RepositoryDeletions::pending)
			.list();
	}

	Optional<Pending> find(UUID setupId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM repo.job_setup WHERE id = :id")
			.param("id", setupId)
			.query(RepositoryDeletions::pending)
			.optional();
	}

	/**
	 * The deletion as one operation, named by the Setup and whether the ZIP comes first; the payload
	 * carries what the operation needs once the row is gone.
	 * @param customerId the ZIP's owner; {@code null} without a ZIP
	 */
	@Transactional
	void request(Pending pending, @Nullable UUID customerId, boolean zip) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("setupId", pending.setupId().toString());
		payload.put("jobId", pending.jobId().toString());
		payload.put("zip", zip);
		if (customerId != null) {
			payload.put("customerId", customerId.toString());
		}
		put(payload, "intermediary", pending.intermediary());
		put(payload, "working", pending.working());
		if (pending.branch() != null) {
			payload.put("branch", pending.branch());
		}
		this.operations.request(new OperationRequest(DELETE,
				UUID.nameUUIDFromBytes((pending.setupId() + ":delete:" + zip).getBytes(StandardCharsets.UTF_8)), payload, null,
				null, 0));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), zip ? "repo.deletion_started" : "repo.deletion_requested",
				AuditObject.of("repo.job_setup", pending.setupId())).forJob(pending.jobId()));
	}

	/**
	 * Stores the final ZIP for the Setup, unless one is stored already.
	 * @return whether the Setup now has its ZIP; {@code false} once its row is gone
	 */
	@Transactional
	boolean archived(UUID setupId, UUID customerId, String fileName, byte[] zip) {
		Optional<UUID> existing = this.jdbc.sql("SELECT archive_file_id FROM repo.job_setup WHERE id = :id FOR UPDATE")
			.param("id", setupId)
			.query((rs, n) -> Optional.ofNullable(rs.getObject("archive_file_id", UUID.class)))
			.optional()
			.orElse(null);
		if (existing == null) {
			return false;
		}
		if (existing.isPresent()) {
			return true;
		}
		StoredFile file = this.files.storeArchive(ZIP_PURPOSE, customerId, fileName, zip);
		this.jdbc.sql("UPDATE repo.job_setup SET archive_file_id = :file WHERE id = :id")
			.param("file", file.id())
			.param("id", setupId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.final_zip_stored", AuditObject.of("repo.job_setup", setupId),
				AuditObject.of("files.stored_file", file.id())));
		return true;
	}

	/** Both repositories read back as gone. A Setup row already deleted with its Product changes nothing. */
	@Transactional
	void deleted(UUID setupId, UUID jobId) {
		int updated = this.jdbc.sql("UPDATE repo.job_setup SET deleted_at = :now WHERE id = :id AND deleted_at IS NULL")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", setupId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), updated == 1 ? "repo.repositories_deleted"
				: "repo.repositories_deleted_with_product", AuditObject.of("repo.job_setup", setupId)).forJob(jobId));
	}

	/** The final ZIPs of the Product's Setups, for its deletion (NFR-10). */
	List<UUID> archivesOfProduct(UUID productId) {
		return this.jdbc.sql("SELECT archive_file_id FROM repo.job_setup WHERE product_id = :id AND archive_file_id IS NOT NULL")
			.param("id", productId)
			.query(UUID.class)
			.list();
	}

	/** Whether the stored file is a final ZIP owned by {@code accountId}. */
	boolean ownsArchive(UUID fileId, UUID accountId) {
		return this.files.find(fileId)
			.filter(file -> ZIP_PURPOSE.equals(file.purpose()) && file.accountId().equals(accountId))
			.isPresent();
	}

	private static void put(Map<String, Object> payload, String name, @Nullable PlatformRepository repository) {
		if (repository != null) {
			payload.put(name + "Id", repository.id());
			payload.put(name + "Name", repository.fullName());
		}
	}

	private static Pending pending(ResultSet rs, int n) throws SQLException {
		return new Pending(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class),
				repository(rs.getObject("intermediary_github_id", Long.class), rs.getString("intermediary_name")),
				repository(rs.getObject("working_github_id", Long.class), rs.getString("working_name")),
				rs.getString("default_branch"), rs.getObject("archive_file_id", UUID.class));
	}

	static @Nullable PlatformRepository repository(@Nullable Long id, @Nullable String fullName) {
		return id == null || fullName == null ? null
				: new PlatformRepository(id, fullName.substring(fullName.indexOf('/') + 1), fullName);
	}

}
