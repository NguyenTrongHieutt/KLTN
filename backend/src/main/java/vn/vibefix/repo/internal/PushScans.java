package vn.vibefix.repo.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
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
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.product.ProductDirectory;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.scanning.Finding;
import vn.vibefix.scanning.FindingCategory;
import vn.vibefix.scanning.ScanCompleted;
import vn.vibefix.scanning.ScanPurpose;
import vn.vibefix.scanning.ScanTarget;
import vn.vibefix.scanning.Scans;
import vn.vibefix.scanning.ShaRange;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Push scans of a Job's Working Repository (FR-8, AD-12, AD-18, SG-16). A {@code push} webhook, or the
 * sweep, makes {@code repo} read every branch and tag head back from GitHub; any new or moved ref requests
 * a {@code PUSH} scan, with a Deadline that alerts Administrators if it waits more than 10 minutes. When
 * the run completes, {@code repo} pushes each new Finding's resolvers down (AD-3): the Mentor for a
 * credential, the Customer for personal data. The resolver gets {@value Scans#RESOLVE_WORK_FINDING}, and
 * the Customer, the Mentor, and the active Crew hear of it.
 */
@Component
class PushScans {

	static final String FINDING_OPENED = "scanning.finding_opened";

	/** FR-8's 15-minute target: an Administrator hears once a push scan has waited this long. */
	static final Duration DELAY = Duration.ofMinutes(10);

	private static final Set<String> RUNNING = Set.of("STARTING", "ACTIVE", "CLOSING");

	private static final Logger log = LoggerFactory.getLogger(PushScans.class);

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final JobSetups setups;

	private final PlatformRepositories repositories;

	private final Scans scans;

	private final ProductDirectory products;

	private final Crews crews;

	private final Notifications notifications;

	private final Todos todos;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	private final TransactionTemplate transactions;

	PushScans(JdbcClient jdbc, JobBusy busy, JobSetups setups, PlatformRepositories repositories, Scans scans,
			ProductDirectory products, Crews crews, Notifications notifications, Todos todos, Deadlines deadlines,
			AuditLog auditLog, ProcessedEvents processedEvents, Clock clock, PlatformTransactionManager transactionManager) {
		this.transactions = new TransactionTemplate(transactionManager);
		this.jdbc = jdbc;
		this.busy = busy;
		this.setups = setups;
		this.repositories = repositories;
		this.scans = scans;
		this.products = products;
		this.crews = crews;
		this.notifications = notifications;
		this.todos = todos;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	/** Setup's copy, in its resolving transaction: the default branch at the approved commit is known, so it is not scanned again. */
	void recordCopy(Setup setup, PlatformRepository working) {
		if (setup.defaultBranch() == null || setup.approvedSha() == null) {
			return;
		}
		upsert(working.id(), setup.jobId(), Map.of("refs/heads/" + setup.defaultBranch(), setup.approvedSha()));
	}

	/** Reads the Working Repository's heads back; a new or moved one requests a push scan. */
	void refresh(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		Setup setup = this.setups.latest(jobId).orElse(null);
		if (job == null || !RUNNING.contains(job.state()) || setup == null || !"DONE".equals(setup.status())
				|| setup.working() == null) {
			return;
		}
		PlatformRepository working = setup.working();
		Map<String, String> heads = this.repositories.refs(working);
		if (!heads.equals(recorded(working.id()))) {
			changed(job, working, heads);
		}
	}

	/** The new heads, and a push scan of the Working Repository (FR-8), in one transaction of its own. */
	void changed(BusyJob job, PlatformRepository working, Map<String, String> heads) {
		this.transactions.executeWithoutResult(status -> record(job, working, heads));
	}

	private void record(BusyJob job, PlatformRepository working, Map<String, String> heads) {
		this.jdbc.sql("DELETE FROM repo.branch_head WHERE repository_github_id = :repository")
			.param("repository", working.id())
			.update();
		upsert(working.id(), job.jobId(), heads);
		String product = this.products.find(job.productId()).map(ProductDirectory.ProductInfo::name).orElse(job.title());
		UUID run = this.scans.requestScan(new ScanTarget(job.productId(), job.customerId(), product, working.fullName(),
				working.id()), ShaRange.wholeHistory(), ScanPurpose.PUSH);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO repo.push_scan (scan_run_id, job_id, product_id, repository_github_id, requested_at)
				VALUES (:run, :job, :product, :repository, :now)
				""").param("run", run).param("job", job.jobId()).param("product", job.productId())
			.param("repository", working.id()).param("now", Timestamp.from(now)).update();
		this.deadlines.schedule(new DeadlineRequest(PushScanDelay.KEY, run, "0", job.jobId(), now.plus(DELAY)));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.push_scan_requested", AuditObject.of("job.job", job.jobId()))
			.forJob(job.jobId()));
	}

	/** A {@code push} event on a Working Repository is a hint: read its heads back. */
	@ApplicationModuleListener
	void onWebhook(GitHubWebhookReceived event) {
		hinted(event);
	}

	/** What the webhook listener does, on the caller's thread. Replay-safe. */
	@Transactional
	void hinted(GitHubWebhookReceived event) {
		if (event.app() != App.PLATFORM || !"push".equals(event.event())
				|| !this.processedEvents.markProcessed("repo.PushScans.onWebhook", event.eventId())) {
			return;
		}
		for (Long repository : event.repositoryIds()) {
			for (UUID jobId : this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_setup WHERE working_github_id = :id")
				.param("id", repository)
				.query(UUID.class)
				.list()) {
				refreshQuietly(jobId);
			}
		}
	}

	/** A push scan completed: each new Finding gets its resolver, to-do, and notices. */
	@ApplicationModuleListener
	void onScanCompleted(ScanCompleted completed) {
		completed(completed);
	}

	/** What the completion listener does, on the caller's thread. Replay-safe. */
	@Transactional
	void completed(ScanCompleted completed) {
		if (completed.purpose() != ScanPurpose.PUSH || completed.openedFindingIds().isEmpty()
				|| !this.processedEvents.markProcessed("repo.PushScans.onScanCompleted", completed.eventId())) {
			return;
		}
		UUID jobId = this.jdbc.sql("SELECT job_id FROM repo.push_scan WHERE scan_run_id = :run")
			.param("run", completed.scanRunId())
			.query(UUID.class)
			.optional()
			.orElse(null);
		BusyJob job = jobId == null ? null : this.busy.job(jobId).orElse(null);
		if (job == null) {
			return;
		}
		String product = this.products.find(job.productId()).map(ProductDirectory.ProductInfo::name).orElse(job.title());
		for (UUID findingId : completed.openedFindingIds()) {
			Finding finding = this.scans.finding(job.productId(), findingId).orElse(null);
			if (finding == null) {
				continue;
			}
			boolean credential = finding.category() == FindingCategory.CREDENTIAL;
			UUID resolver = credential && job.mentorId() != null ? job.mentorId() : job.customerId();
			Audience audience = resolver.equals(job.customerId()) ? Audience.CUSTOMER : Audience.MENTOR;
			this.scans.setResolvers(findingId, Set.of(resolver));
			this.todos.open(new TodoRequest(Scans.RESOLVE_WORK_FINDING, resolver, audience, findingId, Map.of("job", job.title()),
					"/cong-viec/" + jobId, null, jobId));
			this.notifications.send(new NotificationRequest(FINDING_OPENED, findingId, parties(job),
					Map.of("type", finding.type(), "product", product), "/cong-viec/" + jobId, jobId, null));
		}
	}

	/** The Customer, the Mentor, and the active Crew. */
	private List<Recipient> parties(BusyJob job) {
		List<Recipient> parties = new ArrayList<>(List.of(new Recipient(job.customerId(), Audience.CUSTOMER)));
		if (job.mentorId() != null) {
			parties.add(new Recipient(job.mentorId(), Audience.MENTOR));
		}
		for (CrewMember member : this.crews.crewOf(job.jobId(), Crews.Selection.ACTIVE)) {
			parties.add(new Recipient(member.studentId(), Audience.CREW_ACTIVE));
		}
		return parties;
	}

	/** Every Working Repository of a running Job, as the sweep reads them (AD-12). */
	void sweep() {
		for (UUID jobId : this.jdbc.sql("""
				SELECT job_id FROM repo.job_setup WHERE status = 'DONE' AND working_github_id IS NOT NULL
				""").query(UUID.class).list()) {
			// refresh skips a Job that is no longer running, as job reports it.
			refreshQuietly(jobId);
		}
	}

	private void refreshQuietly(UUID jobId) {
		try {
			refresh(jobId);
		}
		catch (GitHubException failed) {
			log.warn("The heads of Job {}'s Working Repository were not read back: {}", jobId, failed.getMessage());
		}
	}

	private Map<String, String> recorded(long repository) {
		Map<String, String> heads = new java.util.TreeMap<>();
		this.jdbc.sql("SELECT ref, sha FROM repo.branch_head WHERE repository_github_id = :repository")
			.param("repository", repository)
			.query((rs, n) -> heads.put(rs.getString("ref"), rs.getString("sha")))
			.list();
		return heads;
	}

	private void upsert(long repository, UUID jobId, Map<String, String> heads) {
		Timestamp now = Timestamp.from(this.clock.instant());
		heads.forEach((ref, sha) -> this.jdbc.sql("""
				INSERT INTO repo.branch_head (repository_github_id, ref, job_id, sha, updated_at)
				VALUES (:repository, :ref, :job, :sha, :now)
				ON CONFLICT (repository_github_id, ref) DO UPDATE SET sha = EXCLUDED.sha, updated_at = EXCLUDED.updated_at
				""").param("repository", repository).param("ref", ref).param("job", jobId).param("sha", sha).param("now", now)
			.update());
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Sweep {

		private final PushScans scans;

		Sweep(PushScans scans) {
			this.scans = scans;
		}

		@Scheduled(fixedDelayString = "${vibefix.repo.push-sweep:PT10M}", initialDelayString = "PT3M")
		void run() {
			this.scans.sweep();
		}

	}

}
