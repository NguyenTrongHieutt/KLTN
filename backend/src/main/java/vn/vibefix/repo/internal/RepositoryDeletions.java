package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.FailedOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.OperationTicket;

/**
 * The platform repositories' deletion (FR-21, FR-38, NFR-10). Thirty days after the Job is Closed or
 * Cancelled, {@value #DELETE} deletes both repositories and reads them back as gone, which ends the Customer's
 * read of the Intermediary Repository (R5); vibefix keeps no copy of the code, so there is nothing to store
 * first (Story 23.10d). Deleting the Product requests the same deletion at once; its payload names the
 * repositories, so the rows may go first.
 */
@Component
class RepositoryDeletions {

	static final String DELETE = "repo.repository_delete";

	/** A Setup whose repositories are not deleted yet. */
	record Pending(UUID setupId, UUID jobId, UUID productId, @Nullable PlatformRepository intermediary,
			@Nullable PlatformRepository working) {
	}

	private static final String COLUMNS = """
			id, job_id, product_id, intermediary_github_id, intermediary_name, working_github_id, working_name
			""";

	private static final String PENDING = " FROM repo.job_setup WHERE deleted_at IS NULL"
			+ " AND (intermediary_github_id IS NOT NULL OR working_github_id IS NOT NULL)";

	private final JdbcClient jdbc;

	private final ExternalOperations operations;

	/** The same bean as {@link #operations}: a second request revives a deletion that ended {@code FAILED}. */
	private final FailedOperations failedOperations;

	/** Looked up on use: the deletion ends the Customer's read grant (Story 23.7). */
	private final ObjectProvider<JobGrants> grants;

	/** Looked up on use: the deletion closes the Customer's fetching duty (Story 23.10b). */
	private final ObjectProvider<Todos> todos;

	private final AuditLog auditLog;

	private final Clock clock;

	RepositoryDeletions(JdbcClient jdbc, ExternalOperations operations, FailedOperations failedOperations,
			ObjectProvider<JobGrants> grants, ObjectProvider<Todos> todos, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.operations = operations;
		this.failedOperations = failedOperations;
		this.grants = grants;
		this.todos = todos;
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

	/**
	 * The deletion as one operation, named by the Setup; the payload carries what the operation needs once the
	 * row is gone. The 30-day deadline and a Product deletion derive the same id from it, so the second request
	 * joins the first rather than queueing a second deletion of the same repositories.
	 * <p>
	 * Joining an operation that already ended {@code FAILED} would swallow the second request, because
	 * {@code FAILED} is terminal until an Administrator retries it (FR-64) -- and a Product deletion goes on to
	 * delete the Setup rows, so nothing would point at the repositories any more. A second request is therefore
	 * the caller asking for the deletion again, and revives it from its first attempt. {@code FAILED} is the only
	 * terminal non-success this type can reach: its handler answers {@code Transient} or succeeds, and the
	 * {@code Obsolete} arm went with the final ZIP (Story 23.10d).
	 */
	@Transactional
	void request(Pending pending) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("setupId", pending.setupId().toString());
		payload.put("jobId", pending.jobId().toString());
		put(payload, "intermediary", pending.intermediary());
		put(payload, "working", pending.working());
		OperationTicket ticket = this.operations.request(new OperationRequest(DELETE,
				UUID.nameUUIDFromBytes((pending.setupId() + ":delete").getBytes(StandardCharsets.UTF_8)), payload, null, null,
				0));
		if (ticket.status() == OperationStatus.FAILED) {
			this.failedOperations.retry(ticket.id());
		}
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.deletion_requested",
				AuditObject.of("repo.job_setup", pending.setupId())).forJob(pending.jobId()));
	}

	/**
	 * Both repositories read back as gone, which ends the Customer's read of the Intermediary Repository (R5). A Setup
	 * row already deleted with its Product changes nothing.
	 * <p>
	 * It also closes {@value Revocations#DOWNLOAD_TODO} (Story 23.10b): that to-do asked the Customer to fetch the
	 * code before this very date, and once the repositories are gone nothing else can close it -- a code receipt is
	 * refused without a readable Intermediary Repository -- so a Customer who never fetched would be left with an
	 * overdue tier-1 to-do for good.
	 * @param intermediaryId the deleted Intermediary Repository's numeric ID, when the Setup had one
	 */
	@Transactional
	void deleted(UUID setupId, UUID jobId, @Nullable Long intermediaryId) {
		int updated = this.jdbc.sql("UPDATE repo.job_setup SET deleted_at = :now WHERE id = :id AND deleted_at IS NULL")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", setupId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), updated == 1 ? "repo.repositories_deleted"
				: "repo.repositories_deleted_with_product", AuditObject.of("repo.job_setup", setupId)).forJob(jobId));
		this.todos.getObject().closeAll(Revocations.DOWNLOAD_TODO, jobId);
		if (intermediaryId != null) {
			this.grants.getObject().intermediaryDeleted(intermediaryId);
		}
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
				repository(rs.getObject("working_github_id", Long.class), rs.getString("working_name")));
	}

	static @Nullable PlatformRepository repository(@Nullable Long id, @Nullable String fullName) {
		return id == null || fullName == null ? null
				: new PlatformRepository(id, fullName.substring(fullName.indexOf('/') + 1), fullName);
	}

}
