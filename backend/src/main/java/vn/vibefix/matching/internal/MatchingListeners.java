package vn.vibefix.matching.internal;

import java.time.Instant;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.MentorApprovalRevoked;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.matching.internal.Applications.EndReason;
import vn.vibefix.shared.ProcessedEvents;
import vn.vibefix.shared.Role;

/**
 * What other modules' facts end (AD-5, AD-9, replay-safe through {@link ProcessedEvents}): J2 closes a
 * Job's open Applications and J3 puts the others on hold (§4.0), and a locked or revoked Mentor's
 * not-yet-selected Applications are withdrawn (FR-13, AD-19).
 */
@Component
class MatchingListeners {

	private final Applications applications;

	private final ProcessedEvents processedEvents;

	MatchingListeners(Applications applications, ProcessedEvents processedEvents) {
		this.applications = applications;
		this.processedEvents = processedEvents;
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		boolean handled = "J2".equals(event.transition()) || "J3".equals(event.transition());
		if (!handled
				|| !this.processedEvents.markProcessed("matching.MatchingListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		if ("J3".equals(event.transition())) {
			this.applications.applySelection(event.jobId(), UUID.fromString(event.facts().get("applicationId")),
					UUID.fromString(event.facts().get("mentorId")), Instant.parse(event.facts().get("ndaDueAt")));
			return;
		}
		this.applications.closeForJob(event.jobId(),
				"LAPSED".equals(event.facts().get("reason")) ? EndReason.JOB_LAPSED : EndReason.JOB_WITHDRAWN);
	}

	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		if (event.role() != Role.MENTOR
				|| !this.processedEvents.markProcessed("matching.MatchingListeners.onAccountLocked", event.eventId())) {
			return;
		}
		this.applications.withdrawForMentor(event.accountId());
	}

	@ApplicationModuleListener
	void onMentorApprovalRevoked(MentorApprovalRevoked event) {
		if (!this.processedEvents.markProcessed("matching.MatchingListeners.onMentorApprovalRevoked", event.eventId())) {
			return;
		}
		this.applications.withdrawForMentor(event.accountId());
	}

}
