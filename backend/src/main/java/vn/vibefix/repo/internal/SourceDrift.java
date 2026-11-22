package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.github.SourcePushed;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.product.ProductSources;
import vn.vibefix.product.ProductSources.Block;
import vn.vibefix.product.ProductSources.Drift;
import vn.vibefix.product.ProductSources.Registered;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.ProcessedEvents;

/**
 * Drift Warnings (FR-20, FR-53, AD-12, R7, Story 24.6): the Source App's own events tell vibefix within seconds that a
 * running Code Job's Source Repository changed, and nothing reads it on an interval. Only the Source App's deliveries
 * act, and only on a running Code Job (Active or Closing, with a {@code DONE} Setup) of a Product they name:
 * <ul>
 * <li>a {@code push} to the repository's default branch ({@link SourcePushed}) warns of new commits from the payload
 * alone, one warning per batch, identified by its {@code after} commit; no head is read from GitHub, stored, or
 * compared;</li>
 * <li>that push, a {@code repository} deleted, archived, renamed or transferred (matched, like a push, by the
 * repository's permanent ID), an {@code installation} deleted or suspended, and an {@code installation_repositories}
 * removal (matched by the Products bound on that installation) each request one read of the Job through the
 * Customer's Source App ({@value #READ}, {@link ProductSources#drift}), which a GitHub failure retries.</li>
 * </ul>
 * The read gives the Source App's block ({@code APP_UNINSTALLED} or {@code GONE}), else a {@code vibefix.yml} that
 * differs, archived, renamed, and transferred, each its own warning; it also records the Product's status, which opens
 * {@code product.install_source_app} or {@code product.add_workflow}. Each warning is stored with what triggered it,
 * and the Customer and the Mentor hear of it once. "Made private" is no drift, and neither is the admin right. A renamed
 * or transferred repository becomes the Final Merge target only after the Customer re-confirms it and still administers
 * it. A delivery is a hint: every state but the push's own commits comes from the read.
 */
@Component
class SourceDrift {

	static final String DETECTED = "repo.drift_detected";

	static final String RECONFIRM = "repo.reconfirm_source";

	/** The drift read a matched delivery requests, once per Job and delivery (Story 24.6). */
	static final String READ = "repo.source_drift_read";

	/**
	 * The Source App's deliveries besides {@code push} that request a read, by event and action. Every other action,
	 * {@code privatized} included, does nothing and reads nothing.
	 */
	static final Map<String, Set<String>> HINTS = Map.of("repository", Set.of("deleted", "archived", "renamed", "transferred"),
			"installation", Set.of("deleted", "suspend"), "installation_repositories", Set.of("removed"));

	private static final Set<String> RUNNING = Set.of("ACTIVE", "CLOSING");

	/** What changed in the Source Repository. */
	enum Kind {

		/** Commits pushed to the default branch after J7, as the Source App's {@code push} delivery names them. */
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

	/** A running Code Job past Setup, with its Source Repository as the Product registered it. */
	private record Running(BusyJob job, Registered registered) {
	}

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final JobSetups setups;

	private final ProductSources sources;

	private final ExternalOperations operations;

	private final Notifications notifications;

	private final Todos todos;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final IdGenerator ids;

	private final Clock clock;

	private final TransactionTemplate transactions;

	SourceDrift(JdbcClient jdbc, JobBusy busy, JobSetups setups, ProductSources sources, ExternalOperations operations,
			Notifications notifications, Todos todos, AuditLog auditLog, ProcessedEvents processedEvents, IdGenerator ids,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.busy = busy;
		this.setups = setups;
		this.sources = sources;
		this.operations = operations;
		this.notifications = notifications;
		this.todos = todos;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.ids = ids;
		this.clock = clock;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** A Source App push: new commits on the default branch warn at once, and the Job is read. */
	@ApplicationModuleListener
	void onPush(SourcePushed event) {
		pushed(event);
	}

	/**
	 * What the push listener does, on the caller's thread. Replay-safe. A push to another ref, or one that deleted the
	 * default branch, does nothing; so does a push before the Job's Setup is {@code DONE}, since the J7 copy pins its own
	 * commit.
	 */
	@Transactional
	void pushed(SourcePushed event) {
		if (!event.toDefaultBranch() || !this.processedEvents.markProcessed("repo.SourceDrift.onPush", event.eventId())) {
			return;
		}
		for (Running running : running(this.sources.productsOfRepository(event.repositoryId()))) {
			commits(running.job(), event.after(), event.commits());
			requestRead(running.job().jobId(), event.deliveryId());
		}
	}

	/** Another Source App delivery that may mean drift: the Job is read. */
	@ApplicationModuleListener
	void onWebhook(GitHubWebhookReceived event) {
		hinted(event);
	}

	/** What the webhook listener does, on the caller's thread. Replay-safe. */
	@Transactional
	void hinted(GitHubWebhookReceived event) {
		Set<String> actions = HINTS.get(event.event());
		if (event.app() != App.SOURCE || actions == null || event.action() == null || !actions.contains(event.action())
				|| !this.processedEvents.markProcessed("repo.SourceDrift.onWebhook", event.eventId())) {
			return;
		}
		List<UUID> products = new ArrayList<>();
		if ("repository".equals(event.event())) {
			event.repositoryIds().forEach(id -> products.addAll(this.sources.productsOfRepository(id)));
		}
		else if (event.installationId() != null) {
			products.addAll(this.sources.productsOfInstallation(event.installationId()));
		}
		for (Running running : running(products)) {
			requestRead(running.job().jobId(), event.deliveryId());
		}
	}

	/**
	 * One push batch, one {@code COMMITS} warning (FR-53): its {@code after} commit is its identity, and it lists the
	 * Job's previous {@code COMMITS} list and then the batch's commits, newest last, at most
	 * {@link ProductSources#MAX_DRIFT_COMMITS} (the newest), so its count is every change since the Job started. A batch
	 * that lists no commit (a push back to an earlier commit, say) counts its {@code after} commit as its one change.
	 * Under the Job's lock, so two batches handled at once never read the same previous list.
	 */
	private void commits(BusyJob job, String after, List<String> pushed) {
		this.transactions.executeWithoutResult(status -> {
			this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.param("key", "repo.source_drift:" + job.jobId())
				.query((rs, n) -> 1)
				.list();
			LinkedHashSet<String> commits = new LinkedHashSet<>(this.jdbc.sql("""
					SELECT commits FROM repo.drift_warning WHERE job_id = :job AND kind = 'COMMITS'
					ORDER BY detected_at DESC, cardinality(commits) DESC, id DESC LIMIT 1
					""").param("job", job.jobId()).query((rs, n) -> list(rs.getArray("commits"))).optional().orElse(List.of()));
			commits.addAll(pushed.isEmpty() ? List.of(after) : pushed);
			List<String> all = List.copyOf(commits);
			int max = ProductSources.MAX_DRIFT_COMMITS;
			warn(job, Kind.COMMITS, after, null, all.size() > max ? all.subList(all.size() - max, all.size()) : all);
		});
	}

	/** One read of the Job through the Source App, keyed by the Job and the delivery that hinted at it. */
	private void requestRead(UUID jobId, String deliveryId) {
		this.operations.request(new OperationRequest(READ,
				UUID.nameUUIDFromBytes((jobId + ":" + deliveryId).getBytes(StandardCharsets.UTF_8)),
				Map.of("jobId", jobId.toString(), "deliveryId", deliveryId), null, null, 0));
	}

	/** The running Code Jobs past Setup of these Products, in ID order. */
	private List<Running> running(List<UUID> products) {
		if (products.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_setup WHERE product_id IN (:products) ORDER BY job_id")
			.param("products", products)
			.query(UUID.class)
			.list()
			.stream()
			.map(this::running)
			.flatMap(Optional::stream)
			.toList();
	}

	/**
	 * The Job, while it is a running Code Job past Setup whose Product is registered. An Assessment Job is none,
	 * although Story 23.11a gave it a {@code DONE} Setup row too: its one read-only copy is pinned to the confirmed
	 * commit and it has no Final Merge target, so there is nothing for the Customer to re-confirm -- and
	 * {@code SourceDriftController} serves neither its warnings nor its re-confirmation.
	 */
	private Optional<Running> running(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (job == null || !job.code() || !RUNNING.contains(job.state())) {
			return Optional.empty();
		}
		Setup setup = this.setups.latest(jobId).orElse(null);
		if (setup == null || !"DONE".equals(setup.status())) {
			return Optional.empty();
		}
		return this.sources.registered(job.productId()).map(registered -> new Running(job, registered));
	}

	/**
	 * {@value #READ}: reads one Job's Source Repository through the Source App and warns of any new change. When the
	 * Source App cannot read it, that is its one kind ({@code APP_UNINSTALLED} or {@code GONE}); otherwise a
	 * {@code vibefix.yml} that differs, archived, and renamed or transferred (by its owner and name) each warn on their
	 * own. No commit is read: the push names them.
	 * @return whether the Job was read; false when it is no longer a running Code Job past Setup
	 * @throws GitHubException when GitHub cannot answer now: nothing is warned, and the operation tries again
	 */
	boolean check(UUID jobId) {
		Running running = running(jobId).orElse(null);
		if (running == null) {
			return false;
		}
		BusyJob job = running.job();
		Registered registered = running.registered();
		Drift drift = this.sources.drift(job.productId());
		Block block = drift.block();
		if (block != null) {
			// The repository cannot be read through the Source App: nothing else is known.
			warn(job, block == Block.SOURCE_APP_MISSING ? Kind.APP_UNINSTALLED : Kind.GONE, null, drift.fullName(),
					List.of());
			return true;
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
		return true;
	}

	/** A new change makes one warning, in its own transaction or the caller's; one already warned makes nothing. */
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
		Timestamp reconfirmed = rs.getTimestamp("reconfirmed_at");
		return new Warning(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class), Kind.valueOf(rs.getString("kind")),
				rs.getString("head_sha"), rs.getString("full_name"), list(rs.getArray("commits")),
				rs.getTimestamp("detected_at").toInstant(), reconfirmed == null ? null : reconfirmed.toInstant());
	}

	private static List<String> list(@Nullable Array commits) throws SQLException {
		return commits == null ? List.of() : Arrays.asList((String[]) commits.getArray());
	}

}
