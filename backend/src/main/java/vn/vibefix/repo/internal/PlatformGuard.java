package vn.vibefix.repo.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.github.PlatformRepositories.PullRequest;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.JobBusy;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The Intermediary Repository stays System-only (FR-19, AD-12, SG-16, SG-22, SM-2). A pull request the
 * System did not open is closed: on an Intermediary Repository always, on a Working Repository when an
 * outsider opened it (logged, not alerted). A privileged event on a platform repository whose sender is
 * neither the Platform App's bot nor a person granted on it alerts every Administrator; the operator
 * account's use alerts them too. Every 10 minutes the sweep reads the organization, the installation,
 * and each running Job's repositories back against what vibefix expects, and alerts any difference once a
 * day; an ended Job's repositories are read until they are deleted, and a revocation in progress may go
 * either way. Every closing and alert is audited.
 */
@Component
class PlatformGuard {

	static final String UNEXPECTED = "github.unexpected_actor";

	static final String OPERATOR = "github.operator_account_used";

	static final String DRIFT = "github.platform_drift";

	/** The events that change a repository or who may change it (AD-12). */
	static final Set<String> PRIVILEGED = Set.of("push", "member", "repository", "repository_ruleset", "pull_request");

	private static final Set<String> RUNNING = Set.of("STARTING", "ACTIVE", "CLOSING");

	/** FR-21: an ended Job's repositories stay, without collaborators, until they are deleted. */
	private static final Set<String> ENDED = Set.of("CLOSED", "CANCELLED");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final Logger log = LoggerFactory.getLogger(PlatformGuard.class);

	/** One of a running Job's platform repositories. */
	record PlatformRepo(UUID jobId, PlatformRepository repository, boolean intermediary) {
	}

	private final JdbcClient jdbc;

	private final JobSetups setups;

	private final JobGrants grants;

	private final JobBusy busy;

	private final PlatformRepositories repositories;

	private final Administrators administrators;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	private final @Nullable String operator;

	PlatformGuard(JdbcClient jdbc, JobSetups setups, JobGrants grants, JobBusy busy, PlatformRepositories repositories,
			Administrators administrators, Notifications notifications, AuditLog auditLog, ProcessedEvents processedEvents,
			Clock clock, @Value("${vibefix.github.operator-login:}") String operator) {
		this.jdbc = jdbc;
		this.setups = setups;
		this.grants = grants;
		this.busy = busy;
		this.repositories = repositories;
		this.administrators = administrators;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.processedEvents = processedEvents;
		this.clock = clock;
		this.operator = operator.isBlank() ? null : operator.toLowerCase(Locale.ROOT);
	}

	@ApplicationModuleListener
	void onWebhook(GitHubWebhookReceived event) {
		hinted(event);
	}

	/** What the webhook listener does, on the caller's thread. Replay-safe. */
	@Transactional
	void hinted(GitHubWebhookReceived event) {
		if (event.app() != App.PLATFORM || !PRIVILEGED.contains(event.event())
				|| !this.processedEvents.markProcessed("repo.PlatformGuard.onWebhook", event.eventId())) {
			return;
		}
		for (Long id : event.repositoryIds()) {
			PlatformRepo repo = running().stream().filter(found -> found.repository().id() == id).findFirst().orElse(null);
			if (repo == null) {
				continue;
			}
			try {
				if ("pull_request".equals(event.event())) {
					closeForeign(repo);
				}
				actor(event, repo);
			}
			catch (GitHubException failed) {
				log.warn("Repository {} was not read back after a {} event: {}", repo.repository().fullName(), event.event(),
						failed.getMessage());
			}
		}
	}

	/** AD-12: the sender must be the bot or someone granted on the repository; the operator account always alerts. */
	private void actor(GitHubWebhookReceived event, PlatformRepo repo) {
		if (event.senderLogin() == null) {
			return;
		}
		String login = event.senderLogin().toLowerCase(Locale.ROOT);
		if (login.equals(this.repositories.botLogin())) {
			return;
		}
		Map<String, String> params = Map.of("login", event.senderLogin(), "repository", repo.repository().fullName());
		if (login.equals(this.operator)) {
			alert(OPERATOR, UUID.nameUUIDFromBytes(event.deliveryId().getBytes(StandardCharsets.UTF_8)), params);
			audit("repo.operator_account_used", repo.jobId());
			return;
		}
		boolean granted = !repo.intermediary() && grantees(repo.jobId()).contains(login);
		if (granted || (!repo.intermediary() && "pull_request".equals(event.event()))) {
			// SG-16: an outsider's pull request on a public Working Repository is closed and logged only.
			return;
		}
		alert(UNEXPECTED, UUID.nameUUIDFromBytes(event.deliveryId().getBytes(StandardCharsets.UTF_8)), params);
		audit("repo.unexpected_actor", repo.jobId());
	}

	/** Closes the open pull requests the System did not open (FR-19) or, on a Working Repository, an outsider did. */
	void closeForeign(PlatformRepo repo) {
		String bot = this.repositories.botLogin();
		Set<String> grantees = repo.intermediary() ? Set.of() : grantees(repo.jobId());
		for (PullRequest pull : this.repositories.openPullRequests(repo.repository())) {
			if (pull.authorLogin().equals(bot) || grantees.contains(pull.authorLogin())) {
				continue;
			}
			this.repositories.closePullRequest(repo.repository(), pull.number());
			log.info("Closed pull request {} on {} opened by {}", pull.number(), repo.repository().fullName(), pull.authorLogin());
			audit(repo.intermediary() ? "repo.foreign_pull_request_closed" : "repo.outsider_pull_request_closed", repo.jobId());
		}
	}

	/**
	 * The organization, the installation, and each running Job's repositories read back against what
	 * vibefix expects (AD-10, AD-11, AD-12); any difference alerts every Administrator, once a day.
	 * @return the differences found
	 */
	List<String> sweep() {
		List<String> differences = new ArrayList<>();
		try {
			differences.addAll(this.repositories.organizationDifferences());
			differences.addAll(this.repositories.organizationPeopleDifferences(this.operator));
			differences.addAll(this.repositories.installationDifferences());
		}
		catch (GitHubException failed) {
			differences.add("the organization could not be read back: HTTP " + failed.status());
		}
		String bot = null;
		for (PlatformRepo repo : running()) {
			try {
				if (bot == null) {
					bot = this.repositories.botLogin();
				}
				closeForeign(repo);
				differences.addAll(repositoryDifferences(repo, bot));
			}
			catch (GitHubException failed) {
				differences.add(repo.repository().fullName() + " could not be read back: HTTP " + failed.status());
			}
		}
		if (!differences.isEmpty()) {
			drift(differences);
		}
		return differences;
	}

	private List<String> repositoryDifferences(PlatformRepo repo, String bot) {
		List<String> differences = new ArrayList<>(this.repositories.featureDifferences(repo.repository()));
		Map<String, String> collaborators = new TreeMap<>(this.repositories.directCollaborators(repo.repository()));
		Set<String> invitees = new TreeSet<>(this.repositories.pendingInvitees(repo.repository()));
		String name = repo.repository().fullName();
		if (repo.intermediary()) {
			// FR-19: nobody but the System writes here, and nobody is a collaborator.
			if (!collaborators.isEmpty()) {
				differences.add(name + ": collaborators " + collaborators.keySet() + " on the Intermediary Repository");
			}
			if (!invitees.isEmpty()) {
				differences.add(name + ": invitations for " + invitees + " on the Intermediary Repository");
			}
			differences.addAll(this.repositories.rulesetDifferences(repo.repository()));
			Set<String> actors = new TreeSet<>(this.repositories.recentActors(repo.repository()));
			actors.remove(bot);
			if (!actors.isEmpty()) {
				differences.add(name + ": recent changes by " + actors + ", not the System (SM-2)");
			}
			return differences;
		}
		Map<String, String> expected = new TreeMap<>();
		Set<String> invited = new TreeSet<>();
		for (Grant grant : this.grants.ofJob(repo.jobId())) {
			String login = grant.login().toLowerCase(Locale.ROOT);
			switch (grant.status()) {
				case "WRITE" -> expected.put(login, "write");
				case "ACCEPTED" -> expected.put(login, "read");
				case "INVITED" -> invited.add(login);
				case "REVOKING" -> {
					// Its operation is removing the access: either way is expected until it reads back.
					collaborators.remove(login);
					invitees.remove(login);
				}
				default -> {
				}
			}
		}
		if (!collaborators.equals(expected)) {
			differences.add(name + ": collaborators " + collaborators + " instead of " + expected);
		}
		if (!invitees.equals(invited)) {
			differences.add(name + ": invitations for " + invitees + " instead of " + invited);
		}
		return differences;
	}

	/** Once a day per set of differences: every Administrator, and the Audit Log. */
	private void drift(List<String> differences) {
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		String joined = String.join("; ", differences);
		UUID cause = UUID.nameUUIDFromBytes((today + ":" + joined).getBytes(StandardCharsets.UTF_8));
		if (!this.processedEvents.markProcessed("repo.PlatformGuard.drift", cause)) {
			return;
		}
		log.warn("The platform organization differs from what vibefix expects: {}", joined);
		alert(DRIFT, cause, Map.of("differences", joined.length() > 500 ? joined.substring(0, 497) + "..." : joined));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "repo.platform_drift",
				AuditObject.of("github.organization", UUID.nameUUIDFromBytes("platform".getBytes(StandardCharsets.UTF_8)))));
	}

	/** Every repository of a Job that is running after its Setup, or ended and not deleted yet. */
	List<PlatformRepo> running() {
		List<PlatformRepo> repos = new ArrayList<>();
		for (UUID jobId : this.jdbc.sql("SELECT DISTINCT job_id FROM repo.job_setup WHERE status = 'DONE' AND deleted_at IS NULL")
			.query(UUID.class)
			.list()) {
			boolean running = this.busy.job(jobId)
				.map(job -> RUNNING.contains(job.state()) || ENDED.contains(job.state()))
				.orElse(false);
			Setup setup = running ? this.setups.latest(jobId).orElse(null) : null;
			if (setup == null || !"DONE".equals(setup.status())) {
				continue;
			}
			if (setup.intermediary() != null) {
				repos.add(new PlatformRepo(jobId, setup.intermediary(), true));
			}
			if (setup.working() != null) {
				repos.add(new PlatformRepo(jobId, setup.working(), false));
			}
		}
		return repos;
	}

	private Set<String> grantees(UUID jobId) {
		Set<String> logins = new TreeSet<>();
		this.grants.ofJob(jobId)
			.stream()
			.filter(grant -> !"REVOKED".equals(grant.status()))
			.forEach(grant -> logins.add(grant.login().toLowerCase(Locale.ROOT)));
		return logins;
	}

	private void alert(String type, UUID cause, Map<String, String> params) {
		List<Recipient> admins = this.administrators.activeIds().stream().map(id -> new Recipient(id, Audience.ADMIN)).toList();
		if (!admins.isEmpty()) {
			this.notifications.send(new NotificationRequest(type, cause, admins, params, "/quan-tri", null, null));
		}
	}

	private void audit(String action, UUID jobId) {
		this.auditLog.record(AuditEntry.of(AuditActor.system(), action, AuditObject.of("job.job", jobId)).forJob(jobId));
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Sweep {

		private final PlatformGuard guard;

		private final org.springframework.transaction.support.TransactionTemplate transactions;

		Sweep(PlatformGuard guard, org.springframework.transaction.PlatformTransactionManager transactionManager) {
			this.guard = guard;
			this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		}

		@Scheduled(fixedDelayString = "${vibefix.repo.platform-sweep:PT10M}", initialDelayString = "PT4M")
		void run() {
			this.transactions.executeWithoutResult(status -> this.guard.sweep());
		}

	}

}
