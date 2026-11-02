package vn.vibefix.work.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.identity.AccountLocked;
import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.CrewMemberRemoved;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.shared.ProcessedEvents;

/**
 * J7 creates the Job's Board and seeds one Story per Acceptance Criterion (FR-22, AD-5); a later
 * confirmed Agreement version seeds the criteria the Board does not have yet (AD-29). Each is
 * replay-safe through {@link ProcessedEvents}, and the criterion's stable ID keys every seeded Story. A
 * lock counts as leaving, so a locked account's open Work Items become unassigned on every Job (Story 20.4): every
 * lock runs it, and an account with no Work Item changes nothing. A member who leaves, or whose lock counted as leaving,
 * loses those of that Job (Story 21.6), and whichever runs second finds nothing to do.
 */
@Component
class WorkListeners {

	private final Boards boards;

	private final ProcessedEvents processedEvents;

	private final Reassignments reassignments;

	WorkListeners(Boards boards, ProcessedEvents processedEvents, Reassignments reassignments) {
		this.boards = boards;
		this.processedEvents = processedEvents;
		this.reassignments = reassignments;
	}

	/** FR-59, FR-82: a locked account's open Work Items become unassigned, for the Mentor to assign again. */
	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		if (this.processedEvents.markProcessed("work.WorkListeners.onAccountLocked", event.eventId())) {
			this.reassignments.unassign(event.accountId());
		}
	}

	/** FR-82: the open Work Items of a member who left the Job become unassigned, for the Mentor to assign again. */
	@ApplicationModuleListener
	void onCrewMemberRemoved(CrewMemberRemoved event) {
		if (this.processedEvents.markProcessed("work.WorkListeners.onCrewMemberRemoved", event.eventId())) {
			this.reassignments.unassign(event.jobId(), event.studentId());
		}
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		if ("J7".equals(event.transition())
				&& this.processedEvents.markProcessed("work.WorkListeners.onJobTransitioned", event.eventId())) {
			this.boards.seed(event.jobId(), true);
		}
	}

	@ApplicationModuleListener
	void onAgreementConfirmed(AgreementConfirmed event) {
		if (event.agreementVersionNo() > 1
				&& this.processedEvents.markProcessed("work.WorkListeners.onAgreementConfirmed", event.eventId())) {
			this.boards.seed(event.jobId(), false);
		}
	}

}
