package vn.vibefix.matching.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.job.CrewRecruitmentClosed;
import vn.vibefix.job.CrewRecruitmentOpened;
import vn.vibefix.job.JobPostHidden;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.matching.internal.Applications.EndReason;
import vn.vibefix.shared.ProcessedEvents;

/**
 * What other modules' facts end (AD-5, AD-9, replay-safe through {@link ProcessedEvents}): J2 closes a
 * Job's open Applications and J3 puts the others on hold (§4.0), both lapse its open invitations
 * (FR-14), and a locked account's not-yet-selected Applications, those on hold included, are withdrawn (FR-13,
 * AD-19): every lock runs it, and an account with no bid changes nothing (Stories 20.3 and 20.4). For the Crew
 * (Story 21.4): J4 opens the Mentor's {@code matching.open_crew_recruitment} to-do, and turning recruitment on, J8 or
 * J10 closes it;
 * {@code CrewRecruitmentClosed} lapses the Job's pending Crew Applications; a lock withdraws the account's pending ones,
 * telling each Mentor; and a hidden Job Post tells its applicants.
 */
@Component
class MatchingListeners {

	private final Applications applications;

	private final Invitations invitations;

	private final CrewApplications crewApplications;

	private final ProcessedEvents processedEvents;

	MatchingListeners(Applications applications, Invitations invitations, CrewApplications crewApplications,
			ProcessedEvents processedEvents) {
		this.applications = applications;
		this.invitations = invitations;
		this.crewApplications = crewApplications;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean handled = List.of("J2", "J3", "J4", "J5", "J8", "J10").contains(event.transition());
		if (!handled
				|| !this.processedEvents.markProcessed("matching.MatchingListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		if ("J2".equals(event.transition()) || "J3".equals(event.transition())) {
			this.invitations.lapseForJob(event.jobId());
		}
		if ("J3".equals(event.transition())) {
			this.applications.applySelection(event.jobId(), UUID.fromString(event.facts().get("applicationId")),
					UUID.fromString(event.facts().get("mentorId")), Instant.parse(event.facts().get("ndaDueAt")));
			return;
		}
		if ("J4".equals(event.transition())) {
			this.applications.closeHeld(event.jobId());
			String due = event.facts().get("agreementDueAt");
			String mentor = event.facts().get("mentorId");
			if (mentor != null && !"null".equals(mentor)) {
				this.crewApplications.openRecruitmentTodo(event.jobId(), UUID.fromString(mentor),
						due == null ? null : Instant.parse(due));
			}
			return;
		}
		if ("J8".equals(event.transition()) || "J10".equals(event.transition())) {
			// Closing or cancelled: no Crew is recruited any more, so J4's to-do closes (UX-DR112).
			this.crewApplications.closeRecruitmentTodo(event.jobId());
			return;
		}
		if ("J5".equals(event.transition())) {
			this.applications.reopen(event.jobId(), UUID.fromString(event.facts().get("applicationId")),
					"DECLINED".equals(event.facts().get("reason")) ? EndReason.DECLINED : EndReason.NOT_SIGNED);
			return;
		}
		this.applications.closeForJob(event.jobId(),
				"LAPSED".equals(event.facts().get("reason")) ? EndReason.JOB_LAPSED : EndReason.JOB_WITHDRAWN);
	}

	/** FR-60, FR-14: a hidden Job Post's invitations lapse and its applicants are told. */
	@ApplicationModuleListener
	void onJobPostHidden(JobPostHidden event) {
		if (!this.processedEvents.markProcessed("matching.MatchingListeners.onJobPostHidden", event.eventId())) {
			return;
		}
		this.invitations.lapseForJob(event.jobId());
		this.applications.postHidden(event.jobId());
		this.crewApplications.postHidden(event.jobId());
	}

	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		if (!this.processedEvents.markProcessed("matching.MatchingListeners.onAccountLocked", event.eventId())) {
			return;
		}
		this.applications.withdrawOpenBids(event.accountId());
		this.crewApplications.withdrawForAccount(event.accountId());
	}

	/** AD-7, AD-41, A-04: the Mentor, J8 or J10 closed recruitment; the Job's pending Crew Applications lapse at once. */
	@ApplicationModuleListener
	void onCrewRecruitmentClosed(CrewRecruitmentClosed event) {
		if (!this.processedEvents.markProcessed("matching.MatchingListeners.onCrewRecruitmentClosed", event.eventId())) {
			return;
		}
		this.crewApplications.lapseForJob(event.jobId(), event.closedBy(), event.closedAt());
	}

	/** UX-DR112: recruitment turned on, so J4's "Mở tuyển thành viên nhóm" to-do closes. */
	@ApplicationModuleListener
	void onCrewRecruitmentOpened(CrewRecruitmentOpened event) {
		if (!this.processedEvents.markProcessed("matching.MatchingListeners.onCrewRecruitmentOpened", event.eventId())) {
			return;
		}
		this.crewApplications.closeRecruitmentTodo(event.jobId());
	}

}
