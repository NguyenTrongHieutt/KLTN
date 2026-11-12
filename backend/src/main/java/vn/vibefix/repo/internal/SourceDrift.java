package vn.vibefix.repo.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubException;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.Block;
import vn.vibefix.product.ProductSources.Drift;
import vn.vibefix.product.ProductSources.Registered;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * Drift Warnings (FR-20, FR-53, AD-12, R7): every day, each Active or Closing Code Job's Source Repository is
 * read through the Customer's Source App ({@link ProductSources#drift}, Story 23.5b); the Platform App reads no
 * Source. Commits past the copy; a repository gone (deleted, removed from the App's selection, or transferred to an
 * account without the App), archived, renamed, or transferred; the Source App uninstalled; or {@code vibefix.yml}
 * removed or changed each make one warning, stored with the commits that triggered it, and the Customer and the
 * Mentor hear of it once. "Made private" is no drift, and neither is the admin right. A renamed or transferred
 * repository becomes the Final Merge target only after the Customer re-confirms it and still administers it.
 */
@Component
class SourceDrift {

	static final String DETECTED = "repo.drift_detected";

	static final String RECONFIRM = "repo.reconfirm_source";

	private static final Set<String> RUNNING = Set.of("ACTIVE", "CLOSING");

	private static final Logger log = LoggerFactory.getLogger(SourceDrift.class);

	/** What changed in the Source Repository. */
	enum Kind {

		/** Commits on the default branch past the copied one. */
		COMMITS,

		/**
		 * The Source App no longer reaches it: deleted, removed from the App's selection, or transferred to an account
		 * without the App, which the Source App cannot tell apart ({@code SOURCE_APP_WRONG_REPOSITORY}).
		 */
		GONE,

		/** Archived on GitHub. */
		ARCHIVED,

		/** Renamed within its account: the Customer re-confirms it. */
		RENAMED,

		/** Transferred to another account with the App: the Customer re-confirms it. */
		TRANSFERRED,

		/** The Source App was uninstalled, suspended, or lost a permission ({@code SOURCE_APP_MISSING}). */
		APP_UNINSTALLED,

		/** {@code vibefix.yml} was removed or changed ({@code WORKFLOW_MISSING}, {@code WORKFLOW_MISMATCH}). */
		WORKFLOW_CHANGED

	}

	/** One Drift Warning, with its evidence. */
	record Warning(UUID id, UUID jobId, Kind kind, @Nullable String headSha, @Nullable String fullName, List<String> commits,
			Instant detectedAt, @Nullable Instant reconfirmedAt) {

		boolean awaitingReconfirmation() {
			return (this.kind == Kind.RENAMED || this.kind == Kind.TRANSFERRED) && this.reconfirmedAt == null;
		}

	}

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final JobSetups setups;

	private final ProductSources sources;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final TransactionTemplate transactions;

	SourceDrift(JdbcClient jdbc, JobBusy busy, JobSetups setups, ProductSources sources, Notifications notifications,
			Todos todos, AuditLog auditLog, IdGenerator ids, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.setups = setups;
		this.sources = sources;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** Every running Code Job past Setup. */
	void poll() {
		// In ID order, so one Job's failure is read the same way every day; it never stops the Jobs after it.
		for (UUID jobId : this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_setup WHERE status = 'DONE' ORDER BY job_id")
			.query(UUID.class)
			.list()) {
			try {
				check(jobId);
			}
			catch (GitHubException failed) {
				log.warn("The Source Repository of Job {} was not read: {}", jobId, failed.getMessage());
			}
			catch (RuntimeException failed) {
				log.warn("The drift check of Job {} failed; the next Job is read anyway", jobId, failed);
			}
		}
	}

	/**
	 * Reads one Job's Source Repository through the Source App and warns of any new change. When the Source App cannot
	 * read it, that is its one kind ({@code APP_UNINSTALLED} or {@code GONE}); otherwise a {@code vibefix.yml} that
	 * differs, archived, renamed or transferred (by its owner and name), and new commits each warn on their own.
	 * @throws GitHubException when GitHub cannot answer now: nothing is warned, and the next day's poll reads again
	 */
	void check(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		Setup setup = this.setups.latest(jobId).orElse(null);
		Registered registered = job == null ? null : this.sources.registered(job.productId()).orElse(null);
		if (job == null || !RUNNING.contains(job.state()) || setup == null || !"DONE".equals(setup.status())
				|| registered == null || setup.approvedSha() == null) {
			return;
		}
		Drift drift = this.sources.drift(job.productId(), setup.approvedSha());
		Block block = drift.block();
		if (block != null) {
			// The repository cannot be read through the Source App: nothing else is known.
			warn(job, block == Block.SOURCE_APP_MISSING ? Kind.APP_UNINSTALLED : Kind.GONE, null, drift.fullName(),
					List.of());
			return;
		}
		if (drift.workflow() != null) {
			warn(job, Kind.WORKFLOW_CHANGED, null, drift.fullName(), List.of());
		}
		if (drift.archived()) {
			warn(job, Kind.ARCHIVED, null, drift.fullName(), List.of());
		}
		if (!drift.fullName().equalsIgnoreCase(registered.repositoryFullName())) {
			String before = registered.repositoryFullName().toLowerCase(Locale.ROOT);
			String after = drift.fullName().toLowerCase(Locale.ROOT);
			boolean moved = !before.substring(0, before.indexOf('/')).equals(after.substring(0, after.indexOf('/')));
			warn(job, moved ? Kind.TRANSFERRED : Kind.RENAMED, null, drift.fullName(), List.of());
		}
		// At most ProductSources.MAX_DRIFT_COMMITS, the newest, as the drift read keeps them.
		List<String> commits = drift.commits();
		if (!commits.isEmpty()) {
			warn(job, Kind.COMMITS, commits.get(commits.size() - 1), null, commits);
		}
	}

	/** A new change makes one warning, in its own transaction; one already warned makes nothing. */
	private void warn(BusyJob job, Kind kind, @Nullable String head, @Nullable String fullName, List<String> commits) {
		this.transactions.executeWithoutResult(status -> {
			UUID id = this.ids.newId();
			int inserted = this.jdbc.sql("""
					INSERT INTO repo.drift_warning (id, job_id, product_id, kind, head_sha, full_name, commits, detected_at)
					VALUES (:id, :job, :product, :kind, :head, :name, :commits, :now)
					ON CONFLICT (job_id, kind, coalesce(head_sha, ''), coalesce(full_name, '')) DO NOTHING
					""")
				.param("id", id).param("job", job.jobId()).param("product", job.productId()).param("kind", kind.name())
				.param("head", head).param("name", fullName).param("commits", commits.toArray(String[]::new))
				.param("now", Timestamp.from(this.clock.instant()))
				.update();
			if (inserted == 0) {
				return;
			}
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.drift_detected", AuditObject.of("repo.drift_warning", id))
				.forJob(job.jobId()));
			List<Recipient> recipients = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
			if (job.mentorId() != null) {
				recipients.add(new Recipient(job.mentorId(), Audience.MENTOR));
			}
			this.notifications.send(new NotificationRequest(DETECTED, id, recipients, Map.of("job", job.title()),
					"/cong-viec/" + job.jobId(), job.jobId(), null));
			if (kind == Kind.RENAMED || kind == Kind.TRANSFERRED) {
				this.todos.open(new TodoRequest(RECONFIRM, job.customerId(), Audience.CUSTOMER, id, Map.of("job", job.title()),
						"/cong-viec/" + job.jobId(), null, job.jobId()));
			}
		});
	}

	/**
	 * "Xác nhận lại dự án gốc" (FR-20): the Customer re-confirms the renamed or transferred repository.
	 * @throws ApiException {@code CONFLICT NOTHING_TO_RECONFIRM}; {@code GUARD_FAILED} with the plain reason
	 */
	@Transactional
	void reconfirm(BusyJob job) {
		Warning warning = warnings(job.jobId()).stream()
			.filter(Warning::awaitingReconfirmation)
			.reduce((first, second) -> second)
			.orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, RepoReason.NOTHING_TO_RECONFIRM));
		Block block = this.sources.reconfirm(job.productId(), warning.fullName());
		if (block != null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, RepoReason.valueOf(block.name()));
		}
		Instant now = this.clock.instant();
		for (Warning awaiting : warnings(job.jobId()).stream().filter(Warning::awaitingReconfirmation).toList()) {
			this.jdbc.sql("UPDATE repo.drift_warning SET reconfirmed_at = :now WHERE id = :id")
				.param("now", Timestamp.from(now))
				.param("id", awaiting.id())
				.update();
			this.todos.close(RECONFIRM, job.customerId(), awaiting.id());
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(job.customerId()), "repo.source_reconfirmed",
				AuditObject.of("repo.drift_warning", warning.id())).forJob(job.jobId()));
	}

	List<Warning> warnings(UUID jobId) {
		return this.jdbc.sql("""
				SELECT id, job_id, kind, head_sha, full_name, commits, detected_at, reconfirmed_at FROM repo.drift_warning
				WHERE job_id = :job ORDER BY detected_at, id
				""").param("job", jobId).query(SourceDrift::warning).list();
	}

	private static Warning warning(ResultSet rs, int n) throws SQLException {
		Array commits = rs.getArray("commits");
		Timestamp reconfirmed = rs.getTimestamp("reconfirmed_at");
		return new Warning(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), Kind.valueOf(rs.getString("kind")),
				rs.getString("head_sha"), rs.getString("full_name"),
				commits == null ? List.of() : Arrays.asList((String[]) commits.getArray()), rs.getTimestamp("detected_at").toInstant(),
				reconfirmed == null ? null : reconfirmed.toInstant());
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Poll {

		private final SourceDrift drift;

		Poll(SourceDrift drift) {
			this.drift = drift;
		}

		@Scheduled(cron = "${vibefix.repo.drift-cron:0 0 5 * * *}", zone = "Asia/Ho_Chi_Minh")
		void run() {
			this.drift.poll();
		}

	}

}
