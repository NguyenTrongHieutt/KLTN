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
import java.util.Optional;
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
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.SourceRepository;
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
import vn.vibefix.product.ProductSources.Registered;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * Drift Warnings (FR-20, FR-53, AD-12): every day, each Active or Closing Code Job's Source Repository is
 * read by its permanent ID. Commits past the copy, a repository gone or private, archived, renamed, or
 * transferred each make one warning, stored with the commits that triggered it, and the Customer and the
 * Mentor hear of it once. A renamed or transferred repository becomes the Final Merge target only after
 * the Customer re-confirms it and still administers it.
 */
@Component
class SourceDrift {

	static final String DETECTED = "repo.drift_detected";

	static final String RECONFIRM = "repo.reconfirm_source";

	private static final Set<String> RUNNING = Set.of("ACTIVE", "CLOSING");

	private static final Logger log = LoggerFactory.getLogger(SourceDrift.class);

	/** What changed in the Source Repository. */
	enum Kind {

		COMMITS, DELETED_OR_PRIVATE, ARCHIVED, RENAMED, TRANSFERRED

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

	private final PlatformRepositories repositories;

	private final ProductSources sources;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final TransactionTemplate transactions;

	SourceDrift(JdbcClient jdbc, JobBusy busy, JobSetups setups, PlatformRepositories repositories, ProductSources sources,
			Notifications notifications, Todos todos, AuditLog auditLog, IdGenerator ids, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.setups = setups;
		this.repositories = repositories;
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
		for (UUID jobId : this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_setup WHERE status = 'DONE'").query(UUID.class).list()) {
			try {
				check(jobId);
			}
			catch (GitHubException failed) {
				log.warn("The Source Repository of Job {} was not read: {}", jobId, failed.getMessage());
			}
		}
	}

	/** Reads one Job's Source Repository by its permanent ID and warns of any new change. */
	void check(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		Setup setup = this.setups.latest(jobId).orElse(null);
		Registered registered = job == null ? null : this.sources.registered(job.productId()).orElse(null);
		if (job == null || !RUNNING.contains(job.state()) || setup == null || !"DONE".equals(setup.status())
				|| registered == null || setup.approvedSha() == null) {
			return;
		}
		Optional<SourceRepository> now = this.repositories.source(registered.repositoryGithubId());
		if (now.isEmpty()) {
			warn(job, Kind.DELETED_OR_PRIVATE, null, registered.repositoryFullName(), List.of());
			return;
		}
		SourceRepository repository = now.get();
		if (repository.archived()) {
			warn(job, Kind.ARCHIVED, null, repository.fullName(), List.of());
		}
		if (!repository.fullName().equalsIgnoreCase(registered.repositoryFullName())) {
			String before = registered.repositoryFullName().toLowerCase(Locale.ROOT);
			String after = repository.fullName().toLowerCase(Locale.ROOT);
			boolean moved = !before.substring(0, before.indexOf('/')).equals(after.substring(0, after.indexOf('/')));
			warn(job, moved ? Kind.TRANSFERRED : Kind.RENAMED, null, repository.fullName(), List.of());
		}
		List<String> commits = this.repositories.commitsSince(repository.fullName(), setup.approvedSha(),
				repository.defaultBranch());
		if (!commits.isEmpty()) {
			List<String> kept = commits.size() > 250 ? commits.subList(commits.size() - 250, commits.size()) : commits;
			warn(job, Kind.COMMITS, kept.get(kept.size() - 1), null, kept);
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
