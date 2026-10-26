package vn.vibefix.matching.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.job.JobPostHidden;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.matching.internal.Applications.EndReason;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.shared.Role;

/**
 * What other modules' facts end (AD-5, AD-9, replay-safe through {@link ProcessedEvents}): J2 closes a
 * Job's open Applications and J3 puts the others on hold (§4.0), both lapse its open invitations
 * (FR-14), and a locked Mentor's not-yet-selected Applications are withdrawn (FR-13, AD-19).
 */
@Component
class MatchingListeners {

	private final Applications applications;

	private final Invitations invitations;

	private final ProcessedEvents processedEvents;

	MatchingListeners(Applications applications, Invitations invitations, ProcessedEvents processedEvents) {
		this.applications = applications;
		this.invitations = invitations;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean handled = List.of("J2", "J3", "J4", "J5").contains(event.transition());
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
	}

	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		if (event.role() != Role.MENTOR
				|| !this.processedEvents.markProcessed("matching.MatchingListeners.onAccountLocked", event.eventId())) {
			return;
		}
		this.applications.withdrawForMentor(event.accountId());
	}

}
