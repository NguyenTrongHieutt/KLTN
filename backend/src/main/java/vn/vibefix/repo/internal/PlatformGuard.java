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
 * The Intermediary Repository stays System-only (FR-19, AD-12, SG-16, SG-22, SM-2): nobody but the System writes to it,
 * and its only grantee is the Customer, with read (R5). Both Job repositories are private and take no ruleset on GitHub
 * Free, so the guarantee is read back from collaborators and invitations. A pull request the System did not open on an
 * Intermediary Repository is closed. A {@code pull_request}, {@code member}, or {@code repository} event on a platform
 * repository whose sender is neither the Platform App's bot nor a person granted on that repository alerts every
 * Administrator; the Customer counts only for {@code member} events on their Intermediary Repository. The operator
 * account's use alerts them too. Every 10 minutes the sweep reads the organization, the runner, the installation, and
 * each running Job's repositories (private, features and forking off, Actions disabled, and the expected collaborators
 * and invitations: the Customer's read on the Intermediary until it is deleted, the team's grants on the Working
 * Repository until J8 or J10) back against what vibefix expects, and alerts any difference once a day; an ended Job's
 * repositories are read until they are deleted, and a revocation in progress may go either way. Every closing and alert
 * is audited.
 */
@Component
class PlatformGuard {

	static final String UNEXPECTED = "github.unexpected_actor";

	static final String OPERATOR = "github.operator_account_used";

	static final String DRIFT = "github.platform_drift";

	/**
	 * The events that change a repository or who may change it (AD-12): the Platform App no longer subscribes
	 * to {@code push} or {@code repository_ruleset} (2026-11-12).
	 */
	static final Set<String> PRIVILEGED = Set.of("member", "repository", "pull_request");

	/**
	 * The privileged events whose sender must be the bot or granted, or {@value #UNEXPECTED} alerts (AD-12): a
	 * {@code repository} event (such as one made public) included.
	 */
	static final Set<String> ACTOR_CHECKED = Set.of("member", "repository", "pull_request");

	private static final Set<String> RUNNING = Set.of("STARTING", "ACTIVE", "CLOSING");

	/** FR-21: an ended Job's repositories stay, without collaborators, until they are deleted. */
	private static final Set<String> ENDED = Set.of("CLOSED", "CANCELLED");

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private static final Logger log = LoggerFactory.getLogger(PlatformGuard.class);

	/**
	 * One of a running Job's platform repositories.
	 * @param teamEnded the Job is past J8 or J10 (Closing, Closed or Cancelled), so the team holds nothing on it (R17)
	 */
	record PlatformRepo(UUID jobId, PlatformRepository repository, boolean intermediary, boolean teamEnded) {
	}

	/** The states after J8 or J10, in which the Mentor and the Crew hold no access (FR-21, R17). */
	private static final Set<String> TEAM_ENDED = Set.of("CLOSING", "CLOSED", "CANCELLED");

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
				if ("pull_request".equals(event.event()) && repo.intermediary()) {
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

	/**
	 * AD-12: the sender of a {@code pull_request}, {@code member}, or {@code repository} event must be the bot or
	 * someone granted on the repository, matched by the sender's numeric GitHub ID (AD-10 "Logins by ID"). On an
	 * Intermediary Repository only the Customer is granted, and only for a {@code member} event, such as the one their
	 * acceptance sends (Story 23.7); a pull request or a repository change of theirs there still alerts. The operator
	 * account always alerts.
	 */
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
		if (!ACTOR_CHECKED.contains(event.event()) || granted(repo, event.event(), event.senderId(), login)) {
			return;
		}
		alert(UNEXPECTED, UUID.nameUUIDFromBytes(event.deliveryId().getBytes(StandardCharsets.UTF_8)), params);
		audit("repo.unexpected_actor", repo.jobId());
	}

	/**
	 * Closes the open pull requests on an Intermediary Repository that the System did not open (FR-19). A
	 * Working Repository is private and its grantees work there as they choose, so nothing is closed on it.
	 */
	void closeForeign(PlatformRepo repo) {
		if (!repo.intermediary()) {
			return;
		}
		String bot = this.repositories.botLogin();
		for (PullRequest pull : this.repositories.openPullRequests(repo.repository())) {
			if (pull.authorLogin().equals(bot)) {
				continue;
			}
			this.repositories.closePullRequest(repo.repository(), pull.number());
			log.info("Closed pull request {} on {} opened by {}", pull.number(), repo.repository().fullName(), pull.authorLogin());
			audit("repo.foreign_pull_request_closed", repo.jobId());
		}
	}

	/**
	 * The organization, the runner, the installation, and each running Job's repositories read back against
	 * what vibefix expects (AD-10, AD-11, AD-12, AD-46); any difference alerts every Administrator, once a day.
	 * @return the differences found
	 */
	List<String> sweep() {
		List<String> differences = new ArrayList<>();
		// Each read on its own: one that GitHub refuses neither hides the others nor is named as another.
		readBack(differences, "the organization", this.repositories::organizationDifferences);
		readBack(differences, "the runner", this.repositories::runnerDifferences);
		readBack(differences, "the organization's people", () -> this.repositories.organizationPeopleDifferences(this.operator));
		readBack(differences, "the installation", this.repositories::installationDifferences);
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

	/** Adds what one organization-level read found, or that {@code what} could not be read back. */
	private static void readBack(List<String> differences, String what, java.util.function.Supplier<List<String>> read) {
		try {
			differences.addAll(read.get());
		}
		catch (GitHubException failed) {
			differences.add(what + " could not be read back: HTTP " + failed.status());
		}
	}

	/**
	 * One repository read back: private, the features and forking off, and Actions disabled (AD-11); then exactly the
	 * expected grants of that repository. On the Intermediary that is the Customer's live read, as an invitation or a
	 * read collaborator, and nobody else, with no recent change but the System's and the live Customer's acceptance
	 * ({@code MemberEvent}) (FR-19, SM-2), which is what keeps it System-only without a ruleset; on the Working
	 * Repository, the team's grants, none after J8 or J10 (R17). A lapsed read expects nothing; the invitation GitHub still
	 * lists once it expired is no difference.
	 */
	private List<String> repositoryDifferences(PlatformRepo repo, String bot) {
		List<String> differences = new ArrayList<>(this.repositories.settingsDifferences(repo.repository()));
		Map<String, String> collaborators = new TreeMap<>(this.repositories.directCollaborators(repo.repository()));
		Set<String> invitees = new TreeSet<>(this.repositories.pendingInvitees(repo.repository()));
		String name = repo.repository().fullName();
		Map<String, String> expected = new TreeMap<>();
		Set<String> invited = new TreeSet<>();
		// The live Customer's logins on this Intermediary, whose acceptance there is their own MemberEvent.
		Set<String> readers = new TreeSet<>();
		for (Grant grant : this.grants.ofJob(repo.jobId())) {
			if (grant.repositoryId() != repo.repository().id()) {
				continue;
			}
			String login = currentLogin(grant).toLowerCase(Locale.ROOT);
			// R5: the Customer's read belongs on the Intermediary only; R17: the team's on Working, until J8 or J10.
			boolean belongs = grant.customer() ? repo.intermediary() : !repo.intermediary() && !repo.teamEnded();
			if (grant.customer() && ("INVITED".equals(grant.status()) || "ACCEPTED".equals(grant.status()))) {
				readers.add(login);
			}
			switch (grant.status()) {
				case "WRITE" -> {
					if (belongs && grant.team()) {
						expected.put(login, "write");
					}
				}
				case "ACCEPTED" -> {
					if (belongs) {
						expected.put(login, "read");
					}
				}
				case "INVITED" -> {
					if (belongs) {
						invited.add(login);
					}
				}
				case "REVOKING" -> {
					// Its operation is removing the access: either way is expected until it reads back.
					collaborators.remove(login);
					invitees.remove(login);
				}
				case "LAPSED" -> {
					// R5: the renewals ran out, and GitHub still lists the expired invitation, which nobody can accept.
					invitees.remove(login);
				}
				default -> {
				}
			}
		}
		String where = repo.intermediary() ? " on the Intermediary Repository" : "";
		if (!collaborators.equals(expected)) {
			differences.add(name + ": collaborators " + collaborators + where + " instead of " + expected);
		}
		if (!invitees.equals(invited)) {
			differences.add(name + ": invitations for " + invitees + where + " instead of " + invited);
		}
		if (repo.intermediary()) {
			// FR-19, SM-2: nobody but the System changes it; the live Customer's acceptance is a MemberEvent of theirs.
			Map<String, Set<String>> actors = new TreeMap<>(this.repositories.recentActors(repo.repository()));
			actors.remove(bot);
			for (String reader : readers) {
				actors.computeIfPresent(reader, (login, types) -> {
					Set<String> rest = new TreeSet<>(types);
					rest.remove("MemberEvent");
					return rest.isEmpty() ? null : rest;
				});
			}
			if (!actors.isEmpty()) {
				differences.add(name + ": recent changes by " + actors.keySet() + ", not the System (SM-2)");
			}
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
			String state = this.busy.job(jobId).map(JobBusy.BusyJob::state).orElse("");
			boolean running = RUNNING.contains(state) || ENDED.contains(state);
			Setup setup = running ? this.setups.latest(jobId).orElse(null) : null;
			if (setup == null || !"DONE".equals(setup.status())) {
				continue;
			}
			boolean teamEnded = TEAM_ENDED.contains(state);
			if (setup.intermediary() != null) {
				repos.add(new PlatformRepo(jobId, setup.intermediary(), true, teamEnded));
			}
			if (setup.working() != null) {
				repos.add(new PlatformRepo(jobId, setup.working(), false, teamEnded));
			}
		}
		return repos;
	}

	/**
	 * Whether the sender holds a live grant on this repository: by numeric GitHub ID, or by login for a grant whose ID is
	 * unknown (one from before Story 19.2 whose account no longer matches) or an event without a sender ID. The
	 * Customer's read counts for a {@code member} event on their Intermediary Repository only (Story 23.7).
	 */
	private boolean granted(PlatformRepo repo, String event, @Nullable Long senderId, String senderLogin) {
		return this.grants.ofJob(repo.jobId())
			.stream()
			.filter(grant -> grant.repositoryId() == repo.repository().id() && !"REVOKED".equals(grant.status())
					&& !"LAPSED".equals(grant.status()))
			.filter(grant -> grant.team() || "member".equals(event))
			.anyMatch(grant -> sameAccount(grant, senderId, senderLogin));
	}

	/** The grant's person is this GitHub account: by numeric ID when both are known, else by lower-case login. */
	private boolean sameAccount(Grant grant, @Nullable Long githubUserId, String login) {
		Long id = this.grants.githubUserId(grant);
		return id != null && githubUserId != null ? id.equals(githubUserId)
				: grant.login().toLowerCase(Locale.ROOT).equals(login.toLowerCase(Locale.ROOT));
	}

	/**
	 * The login GitHub uses now for the grant's person (AD-10 "Logins by ID"), stored when it changed; the
	 * last one when GitHub has no account with the ID any more.
	 */
	private String currentLogin(Grant grant) {
		String login = this.grants.currentLogin(grant, this.repositories).orElse(grant.login());
		this.grants.renamed(grant, login);
		return login;
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
