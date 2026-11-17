package vn.vibefix.repo.internal;

import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubWebhookReceived;
import vn.vibefix.github.PlatformRepositories;
import vn.vibefix.github.PlatformRepositories.PlatformRepository;
import vn.vibefix.job.CrewMemberAdded;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobBusy.BusyJob;
import vn.vibefix.repo.internal.JobGrants.Grant;
import vn.vibefix.repo.internal.JobGrants.Person;
import vn.vibefix.repo.internal.JobSetups.Setup;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The hints of acceptance (AD-11, AD-12): a {@code member} webhook on a Working Repository and the sweep
 * make {@code repo} read a Job's grants back from GitHub. People are matched by their numeric GitHub ID
 * (AD-10 "Logins by ID"), so a renamed account still counts: an invitee whose ID is among the collaborators
 * accepted; an accepted one among the outside collaborators without two-factor authentication is not ready; a drifted
 * organization member who has left the organization loses any direct grant and is invited again. Then
 * Verify may start (Starting), or an accepted late member is raised (Active). It also grants a counted person with no
 * grant once the Job's Setup is done, as a late member, so a signature that committed during Setup's resolving
 * transaction is not missed. The Customer's read on the Intermediary Repository (R5, Story 23.7) is read back the same
 * way in any Job state until the repositories are deleted, and a {@code member} event on the Intermediary hints it; it
 * is never raised and never holds Verify back. A Customer who declined the invitation (none pending, no collaborator) or
 * left after accepting (no collaborator) lapses.
 * <p>
 * On an Assessment Job (R6, Story 23.11b) the team's grants are reads of its one copy: they are read back the same way,
 * but no Verify is reserved and no raise is requested for them, whether the member was there at the Setup or joined
 * after it. Once each of them is accepted the Job is settled, so {@link JobGrants#unsettledJobs()} drops it and the
 * sweep reads GitHub for it no more.
 */
@Component
class GrantChecks {

	private static final Logger log = LoggerFactory.getLogger(GrantChecks.class);

	private final JobGrants grants;

	private final JobSetups setups;

	private final JobBusy busy;

	private final PlatformRepositories repositories;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	GrantChecks(JobGrants grants, JobSetups setups, JobBusy busy, PlatformRepositories repositories,
			ProcessedEvents processedEvents, Clock clock) {
		this.grants = grants;
		this.setups = setups;
		this.busy = busy;
		this.repositories = repositories;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	/**
	 * Reads the Job's grants back and acts on what changed: the team's while the Job is Starting or Active, the
	 * Customer's read in any state until the repositories are deleted (R5).
	 */
	void check(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (job == null) {
			return;
		}
		boolean running = "STARTING".equals(job.state()) || "ACTIVE".equals(job.state());
		Setup setup = this.setups.latest(jobId).orElse(null);
		PlatformRepository team = setup != null && "DONE".equals(setup.status()) ? setup.teamRepository() : null;
		if (running && setup != null && team != null) {
			// grantAll read the Crew before such a signature, and its listener found Setup not done. An existing grant stays.
			// R6: an Assessment Job's copy is granted with read alone, which the row carries and no path raises.
			for (Person person : this.grants.counted(job)) {
				this.grants.grant(job, person, team, true, setup.assessment());
			}
		}
		Map<Long, Set<Long>> collaborators = new HashMap<>();
		for (Grant grant : this.grants.ofJob(jobId)) {
			if (!running && grant.team()) {
				continue;
			}
			switch (grant.status()) {
				case "INVITED" -> {
					// R5: the Customer may decline. Their pending invitations are read before the collaborators, so an
					// acceptance in between reads as an acceptance, never as a decline; an expired one is the renewal's.
					boolean unexpired = grant.expiresAt() != null && this.clock.instant().isBefore(grant.expiresAt());
					Set<String> pending = grant.customer() && unexpired ? this.repositories.pendingInvitees(grant.repository())
							: null;
					if (collaborator(grant, collaborators)) {
						this.grants.accepted(grant);
					}
					else if (pending != null && !pending.contains(currentLogin(grant).toLowerCase(Locale.ROOT))) {
						this.grants.lapse(grant, JobGrants.DECLINED);
					}
				}
				case "ACCEPTED" -> {
					// R5: a Customer who left the Intermediary after accepting reads it no more.
					if (grant.customer() && !collaborator(grant, collaborators)) {
						this.grants.lapse(grant, JobGrants.LEFT);
					}
				}
				case "MEMBER_DRIFT" -> {
					// AD-10 "Logins by ID": the login GitHub uses now, stored when it changed.
					String login = this.grants.currentLogin(grant, this.repositories).orElse(grant.login());
					this.grants.renamed(grant, login);
					if (!this.repositories.organizationMember(login)) {
						this.repositories.removeCollaborator(grant.repository(), login);
						this.grants.reinvite(grant);
					}
				}
				default -> {
				}
			}
		}
		Map<Long, String> withoutTwoFactor = null;
		for (Grant grant : this.grants.ofJob(jobId)) {
			if (!running && grant.team()) {
				continue;
			}
			if ("ACCEPTED".equals(grant.status()) || "WRITE".equals(grant.status())) {
				if (withoutTwoFactor == null) {
					withoutTwoFactor = this.repositories.withoutTwoFactor();
				}
				Long id = this.grants.githubUserId(grant);
				this.grants.twoFactor(grant, id != null ? withoutTwoFactor.containsKey(id)
						: withoutTwoFactor.containsValue(grant.login().toLowerCase(Locale.ROOT)));
			}
		}
		// FR-18, AD-11, NFR-6, Story 21.5: a member who joined after Setup is raised to write once accepted on an Active
		// Job, by a consequence operation that changes no Job state. Accepted while Starting, they wait for J7: the
		// operation's key is fixed, and asked too early it would end obsolete and never run again.
		// R6: a read-only grant on an Assessment Job's copy is never raised, whoever holds it and however late they joined.
		if ("ACTIVE".equals(job.state())) {
			for (Grant grant : this.grants.ofJob(jobId)) {
				if (grant.team() && !grant.readOnly() && grant.crewChange() && "ACCEPTED".equals(grant.status())
						&& !grant.twoFactorMissing()) {
					this.grants.requestRaise(grant);
				}
			}
		}
		if ("STARTING".equals(job.state()) && setup != null) {
			this.grants.maybeVerify(jobId, setup);
		}
	}

	/** Whether GitHub shows the grant's person as a collaborator now: by numeric ID (AD-10), else by login. */
	private boolean collaborator(Grant grant, Map<Long, Set<Long>> collaborators) {
		Long id = this.grants.githubUserId(grant);
		return id != null
				? collaborators
					.computeIfAbsent(grant.repositoryId(), repository -> this.repositories.collaboratorIds(grant.repository()))
					.contains(id)
				: this.repositories.collaborator(grant.repository(), grant.login());
	}

	/** The login GitHub uses now for the grant's person (AD-10), stored when it changed; the last one when it is gone. */
	private String currentLogin(Grant grant) {
		String login = this.grants.currentLogin(grant, this.repositories).orElse(grant.login());
		this.grants.renamed(grant, login);
		return login;
	}

	/**
	 * A {@code member} event on a Working Repository, or on an Intermediary Repository the Customer is invited to, is a
	 * hint: read that Job's grants back.
	 */
	@ApplicationModuleListener
	void onWebhook(GitHubWebhookReceived event) {
		hinted(event);
	}

	/** What the webhook listener does, on the caller's thread. Replay-safe. */
	@Transactional
	void hinted(GitHubWebhookReceived event) {
		if (event.app() != App.PLATFORM || !"member".equals(event.event())
				|| !this.processedEvents.markProcessed("repo.GrantChecks.onWebhook", event.eventId())) {
			return;
		}
		for (Long repository : event.repositoryIds()) {
			for (UUID jobId : this.grants.jobsOf(repository)) {
				checkQuietly(jobId);
			}
		}
	}

	/**
	 * A member signed and joined a Job whose Setup is done (FR-18, AD-11, SG-67, Story 21.5), Starting or Active: they
	 * are invited with read permission as a late member, read back by numeric ID, and raised to write on acceptance;
	 * Verify never waits for them. On an Assessment Job (R6, Story 23.11b) they are invited to read its one copy and
	 * never raised, since the grant is read-only.
	 */
	@ApplicationModuleListener
	void onCrewMemberAdded(CrewMemberAdded event) {
		added(event);
	}

	/** What the Crew listener does, on the caller's thread. Replay-safe. */
	@Transactional
	void added(CrewMemberAdded event) {
		if (!this.processedEvents.markProcessed("repo.GrantChecks.onCrewMemberAdded", event.eventId())) {
			return;
		}
		BusyJob job = this.busy.job(event.jobId()).orElse(null);
		Setup setup = this.setups.latest(event.jobId()).orElse(null);
		PlatformRepository team = setup != null && "DONE".equals(setup.status()) ? setup.teamRepository() : null;
		if (job == null || setup == null || team == null) {
			return;
		}
		if (!"STARTING".equals(job.state()) && !"ACTIVE".equals(job.state())) {
			return;
		}
		for (Person person : this.grants.counted(job)) {
			if (person.accountId().equals(event.studentId())) {
				this.grants.grant(job, person, team, true, setup.assessment());
			}
		}
	}

	/** Every unsettled Job, as the sweep reads them (AD-12's fallback when no webhook arrives). */
	void sweep() {
		for (UUID jobId : this.grants.unsettledJobs()) {
			checkQuietly(jobId);
		}
	}

	private void checkQuietly(UUID jobId) {
		try {
			check(jobId);
		}
		catch (GitHubException failed) {
			log.warn("The grants of Job {} were not read back: {}", jobId, failed.getMessage());
		}
	}

	@Component
	@ConditionalOnBooleanProperty(name = "vibefix.scheduling.enabled", matchIfMissing = true)
	static class Sweep {

		private final GrantChecks checks;

		Sweep(GrantChecks checks) {
			this.checks = checks;
		}

		@Scheduled(fixedDelayString = "${vibefix.repo.grant-sweep:PT10M}", initialDelayString = "PT2M")
		void run() {
			this.checks.sweep();
		}

	}

}
