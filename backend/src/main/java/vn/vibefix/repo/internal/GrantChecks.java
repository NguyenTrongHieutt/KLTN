package vn.vibefix.repo.internal;

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
 * transaction is not missed.
 */
@Component
class GrantChecks {

	private static final Logger log = LoggerFactory.getLogger(GrantChecks.class);

	private final JobGrants grants;

	private final JobSetups setups;

	private final JobBusy busy;

	private final PlatformRepositories repositories;

	private final ProcessedEvents processedEvents;

	GrantChecks(JobGrants grants, JobSetups setups, JobBusy busy, PlatformRepositories repositories,
			ProcessedEvents processedEvents) {
		this.grants = grants;
		this.setups = setups;
		this.busy = busy;
		this.repositories = repositories;
		this.processedEvents = processedEvents;
	}

	/** Reads the Job's grants back and acts on what changed. */
	void check(UUID jobId) {
		BusyJob job = this.busy.job(jobId).orElse(null);
		if (job == null || !("STARTING".equals(job.state()) || "ACTIVE".equals(job.state()))) {
			return;
		}
		Setup setup = this.setups.latest(jobId).orElse(null);
		if (job.code() && setup != null && "DONE".equals(setup.status()) && setup.working() != null) {
			// grantAll read the Crew before such a signature, and its listener found Setup not done. An existing grant stays.
			for (Person person : this.grants.counted(job)) {
				this.grants.grant(job, person, setup.working(), true);
			}
		}
		Map<Long, Set<Long>> collaborators = new HashMap<>();
		for (Grant grant : this.grants.ofJob(jobId)) {
			switch (grant.status()) {
				case "INVITED" -> {
					Long id = this.grants.githubUserId(grant);
					boolean accepted = id != null
							? collaborators
								.computeIfAbsent(grant.repositoryId(), repository -> this.repositories.collaboratorIds(grant.repository()))
								.contains(id)
							: this.repositories.collaborator(grant.repository(), grant.login());
					if (accepted) {
						this.grants.accepted(grant);
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
		if ("ACTIVE".equals(job.state())) {
			for (Grant grant : this.grants.ofJob(jobId)) {
				if (grant.crewChange() && "ACCEPTED".equals(grant.status()) && !grant.twoFactorMissing()) {
					this.grants.requestRaise(grant);
				}
			}
		}
		if ("STARTING".equals(job.state()) && setup != null) {
			this.grants.maybeVerify(jobId, setup);
		}
	}

	/** A {@code member} event on a Working Repository is a hint: read that Job's grants back. */
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
	 * A member signed and joined a Code Job whose Setup is done (FR-18, AD-11, SG-67, Story 21.5), Starting or Active:
	 * they are invited with read permission as a late member, read back by numeric ID, and raised to write on
	 * acceptance; Verify never waits for them.
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
		if (job == null || !job.code() || setup == null || !"DONE".equals(setup.status()) || setup.workingId() == null) {
			return;
		}
		if (!"STARTING".equals(job.state()) && !"ACTIVE".equals(job.state())) {
			return;
		}
		for (Person person : this.grants.counted(job)) {
			if (person.accountId().equals(event.studentId())) {
				this.grants.grant(job, person, setup.working(), true);
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
