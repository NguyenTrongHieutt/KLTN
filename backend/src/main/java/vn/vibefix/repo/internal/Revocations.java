package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Revocation (FR-21, FR-59, FR-82, AD-11, AD-19, R17). J8 or J10 revokes every grant of the Mentor and the Crew on the
 * Job, read included, and leaves the Customer's read on the Intermediary Repository (R5), which lasts until the
 * repositories are deleted; a locked account revokes that person's grants on every Job, the Customer's read included; a
 * member who leaves, or whose lock counted as leaving, revokes their own on that Job; an erased Customer's read is
 * revoked before its rows go. A revoked grant is {@code REVOKING} at once, which supersedes its pending invitation or
 * raise, since those act only on their own status; its operation removes the access and reads the removal back, and
 * Administrators hear if it is still not done 15 minutes later. Reaching Closed or Cancelled also schedules the
 * repositories' deletion 30 days later. Unlocking restores nothing.
 */
@Component
class Revocations {

	static final String REVOKE = "repo.grant_revoke";

	static final String REVOKED = "repo.access_revoked";

	static final String DELAYED = "operations.revocation_delayed";

	/** FR-21: Administrators hear of a revocation still retrying this long after its trigger. */
	static final Duration DELAY = Duration.ofMinutes(15);

	/** FR-21: the platform repositories go this long after the Job ends. */
	static final Duration KEEP = Duration.ofDays(30);

	private static final Set<String> ENDED = Set.of("CLOSED", "CANCELLED");

	private final JdbcClient jdbc;

	private final JobGrants grants;

	private final RepositoryDeletions deletions;

	private final JobBusy busy;

	private final ExternalOperations operations;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Todos todos;

	private final Administrators administrators;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	Revocations(JdbcClient jdbc, JobGrants grants, RepositoryDeletions deletions, JobBusy busy,
			ExternalOperations operations, Deadlines deadlines, Notifications notifications, Todos todos,
			Administrators administrators, AuditLog auditLog, ProcessedEvents processedEvents, Clock clock) {
		this.jdbc = jdbc;
		this.grants = grants;
		this.deletions = deletions;
		this.busy = busy;
		this.operations = operations;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.todos = todos;
		this.administrators = administrators;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		transitioned(event);
	}

	/** What the transition listener does, in the caller's transaction. Replay-safe. */
	@Transactional
	void transitioned(JobTransitioned event) {
		boolean revoke = "J8".equals(event.transition()) || "J10".equals(event.transition());
		boolean ended = ENDED.contains(event.to());
		if ((!revoke && !ended)
				|| !this.processedEvents.markProcessed("repo.Revocations.onJobTransitioned", event.eventId())) {
			return;
		}
		if (revoke) {
			// R17: the team loses every access; the Customer keeps reading the Intermediary until it is deleted (R5).
			this.grants.ofJob(event.jobId()).stream().filter(Grant::team).forEach(this::revoke);
		}
		if (ended && !this.deletions.pending(event.jobId()).isEmpty()) {
			this.deadlines.schedule(new DeadlineRequest(RepositoryDeletion.KEY, event.jobId(), "0", event.jobId(),
					this.clock.instant().plus(KEEP)));
		}
	}

	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		locked(event);
	}

	/** A locked person's grants end on every Job (AD-19). Replay-safe. */
	@Transactional
	void locked(AccountLocked event) {
		if (!this.processedEvents.markProcessed("repo.Revocations.onAccountLocked", event.eventId())) {
			return;
		}
		this.jdbc.sql("""
				SELECT id FROM repo.job_grant WHERE account_id = :account AND status NOT IN ('REVOKING', 'REVOKED', 'LAPSED')
				""")
			.param("account", event.accountId())
			.query(UUID.class)
			.list()
			.forEach(grantId -> this.grants.find(grantId).ifPresent(this::revoke));
	}

	@ApplicationModuleListener
	void onCrewMemberRemoved(CrewMemberRemoved event) {
		removed(event);
	}

	/**
	 * A member left the Job (FR-82), or a lock counted as leaving: their grant ends. Replay-safe, and a grant the lock's own
	 * listener already revoked changes nothing.
	 */
	@Transactional
	void removed(CrewMemberRemoved event) {
		if (!this.processedEvents.markProcessed("repo.Revocations.onCrewMemberRemoved", event.eventId())) {
			return;
		}
		this.grants.ofJob(event.jobId())
			.stream()
			.filter(grant -> grant.team() && grant.accountId().equals(event.studentId()))
			.forEach(this::revoke);
	}

	/**
	 * The account is being erased (AD-24, FR-59): before {@code repo}'s erasure deletes its rows, every live read the
	 * Customer holds on an Intermediary Repository is revoked on GitHub and read back. The operation keeps no name: its
	 * payload holds the grant, the Job, and the repository, never the person, and an Intermediary Repository's only
	 * grantee is its Customer, so it removes every collaborator and invitation there and reads it back empty (FR-19).
	 * Nothing is told to the person. As for any revocation, Administrators hear if it is still not done 15 minutes later:
	 * that Deadline names the grant and reads the operation, since no grant row is left to read.
	 */
	@Transactional
	void erased(UUID accountId) {
		List<UUID> live = this.jdbc.sql("""
				SELECT id FROM repo.job_grant WHERE account_id = :account AND role = 'CUSTOMER' AND status NOT IN ('REVOKED', 'LAPSED')
				""").param("account", accountId).query(UUID.class).list();
		for (UUID grantId : live) {
			Grant grant = this.grants.find(grantId).orElse(null);
			if (grant == null) {
				continue;
			}
			audit("repo.grant_revoking", grant);
			this.todos.close(grant.acceptTodo(), grant.accountId(), grant.id());
			Map<String, Object> payload = Map.of("grantId", grant.id().toString(), "jobId", grant.jobId().toString(),
					"repositoryId", grant.repositoryId(), "repositoryName", grant.repositoryName());
			this.operations.request(new OperationRequest(REVOKE, erasedCause(grant.id()), payload, null, null, 0));
			this.deadlines.schedule(new DeadlineRequest(RevocationDelay.KEY, grant.id(), RevocationDelay.ERASED,
					grant.jobId(), this.clock.instant().plus(DELAY)));
		}
	}

	/** The causing record of an erased Customer's revocation, which keys its operation. */
	static UUID erasedCause(UUID grantId) {
		return key(grantId + ":erased");
	}

	/** Whether an erased Customer's revocation of {@code grantId} read back as gone. */
	boolean erasedRevocationDone(UUID grantId) {
		return this.operations.find(REVOKE + ":" + erasedCause(grantId))
			.map(ticket -> ticket.status() == OperationStatus.SUCCEEDED)
			.orElse(true);
	}

	/**
	 * An erased Customer's read on the Intermediary Repository read back as gone: audited by the grant and the Job, whose
	 * {@code repo.grant_revoking} entry names the account.
	 */
	@Transactional
	void erasedRevoked(UUID grantId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.grant_revoked", AuditObject.of("repo.job_grant", grantId))
			.forJob(jobId));
	}

	/**
	 * An erased Customer's revocation still not read back 15 minutes after the erasure: every Administrator hears, once,
	 * as for any delayed revocation; the person is named by no login, since their account is erased.
	 */
	@Transactional
	void erasureDelayed(UUID grantId, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.revocation_delayed",
				AuditObject.of("repo.job_grant", grantId)).forJob(jobId));
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(DELAYED, key(grantId + ":erased:delayed"), admins,
					Map.of("login", ERASED_LOGIN, "job", title(jobId)), "/quan-tri", null, null));
		}
	}

	/** What a delayed revocation's alert names in place of an erased Customer's GitHub login. */
	static final String ERASED_LOGIN = "khach-hang-da-xoa-tai-khoan";

	/** The grant ends: {@code REVOKING} at once, its invitation to-do closed, the operation, and the alert. */
	private void revoke(Grant grant) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_grant SET status = 'REVOKING' WHERE id = :id AND status NOT IN ('REVOKING', 'REVOKED', 'LAPSED')
				""").param("id", grant.id()).update();
		if (updated == 0) {
			return;
		}
		audit("repo.grant_revoking", grant);
		this.todos.close(grant.acceptTodo(), grant.accountId(), grant.id());
		this.operations.request(new OperationRequest(REVOKE, key(grant.id() + ":revoke"),
				Map.of("grantId", grant.id().toString()), null, null, 0));
		this.deadlines.schedule(new DeadlineRequest(RevocationDelay.KEY, grant.id(), "0", grant.jobId(),
				this.clock.instant().plus(DELAY)));
	}

	/**
	 * Read back as gone: {@code REVOKED}, audited per person and repository, and the person hears; the Customer, whose
	 * read only a lock revokes this way, is not told (no {@value #REVOKED}: that text is about the Working Repository).
	 */
	@Transactional
	void revoked(Grant grant) {
		int updated = this.jdbc.sql("""
				UPDATE repo.job_grant SET status = 'REVOKED', revoked_at = :now WHERE id = :id AND status = 'REVOKING'
				""").param("now", Timestamp.from(this.clock.instant())).param("id", grant.id()).update();
		if (updated == 0) {
			return;
		}
		audit("repo.grant_revoked", grant);
		if (grant.customer()) {
			return;
		}
		Audience audience = "MENTOR".equals(grant.role()) ? Audience.MENTOR : Audience.CREW_EVER_ACTIVE;
		this.notifications.send(new NotificationRequest(REVOKED, key(grant.id() + ":revoked"),
				List.of(new Recipient(grant.accountId(), audience)), Map.of("job", title(grant.jobId())),
				"/cong-viec/" + grant.jobId(), grant.jobId(), null));
	}

	/** Still {@code REVOKING} 15 minutes after its trigger: every Administrator hears, once. */
	@Transactional
	void delayed(Grant grant) {
		audit("repo.revocation_delayed", grant);
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(DELAYED, key(grant.id() + ":delayed"), admins,
					Map.of("login", grant.login(), "job", title(grant.jobId())), "/quan-tri", null, null));
		}
	}

	private String title(UUID jobId) {
		return this.busy.job(jobId).map(BusyJob::title).orElse("");
	}

	private void audit(String action, Grant grant) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of("repo.job_grant", grant.id()),
				AuditObject.of("identity.account", grant.accountId())).forJob(grant.jobId()));
	}

	private static UUID key(String text) {
		return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
	}

}
