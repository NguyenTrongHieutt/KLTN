package vn.vibefix.repo.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.repo.WorkingHeadMoved;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The branch and tag heads of a Job's Working Repository (AD-12). A {@code push} webhook, or the sweep,
 * makes {@code repo} read every head back from GitHub and record it; a moved default-branch head publishes
 * {@link WorkingHeadMoved}, which may void a handover (FR-32). vibefix scans no code (Story 23.1), so a push
 * requests nothing more; Story 23.2 retires the head tracking itself.
 */
@Component
class PushScans {

	private static final Set<String> RUNNING = Set.of("STARTING", "ACTIVE", "CLOSING");

	private static final Logger log = LoggerFactory.getLogger(PushScans.class);

	private final JdbcClient jdbc;

	private final JobBusy busy;

	private final JobSetups setups;

	private final PlatformRepositories repositories;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	private final TransactionTemplate transactions;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	PushScans(JdbcClient jdbc, JobBusy busy, JobSetups setups, PlatformRepositories repositories,
			ProcessedEvents processedEvents, Clock clock, PlatformTransactionManager transactionManager,
			ApplicationEventPublisher events, IdGenerator ids) {
		this.transactions = new TransactionTemplate(transactionManager);
		this.events = events;
		this.ids = ids;
		this.jdbc = jdbc;
		this.busy = busy;
		this.setups = setups;
		this.repositories = repositories;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	/** Setup's copy, in its resolving transaction: the default branch starts at the approved commit. */
	void recordCopy(Setup setup, PlatformRepository working) {
		if (setup.defaultBranch() == null || setup.approvedSha() == null) {
			return;
		}
		upsert(working.id(), setup.jobId(), Map.of("refs/heads/" + setup.defaultBranch(), setup.approvedSha()));
	}

	/** Reads the Working Repository's heads back and records any that are new or moved. */
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

	/** The new heads, in one transaction of its own. */
	void changed(BusyJob job, PlatformRepository working, Map<String, String> heads) {
		this.transactions.executeWithoutResult(status -> record(job, working, heads));
	}

	private void record(BusyJob job, PlatformRepository working, Map<String, String> heads) {
		Map<String, String> before = recorded(working.id());
		this.jdbc.sql("DELETE FROM repo.branch_head WHERE repository_github_id = :repository")
			.param("repository", working.id())
			.update();
		upsert(working.id(), job.jobId(), heads);
		// FR-32: a new default-branch head may void a handover (M3), which delivery decides.
		this.setups.latest(job.jobId()).map(Setup::defaultBranch).ifPresent(branch -> {
			String sha = heads.get("refs/heads/" + branch);
			if (sha != null && !sha.equals(before.get("refs/heads/" + branch))) {
				this.events.publishEvent(new WorkingHeadMoved(this.ids.newId(), job.jobId(), branch, sha));
			}
		});
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
