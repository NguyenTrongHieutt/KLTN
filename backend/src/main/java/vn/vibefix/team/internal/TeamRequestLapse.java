package vn.vibefix.team.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-6, AD-37): an invitation or a join request lapses 14 days after it was sent. Not
 * pausable, since no Job freezes it; the generation is the request itself, so an answered request
 * leaves it {@code OBSOLETE}. The requests are looked up on use: {@code timers} collects its
 * handlers when it is built, before the commands that schedule Deadlines exist.
 */
@Component
class TeamRequestLapse implements DeadlineHandler {

	static final String KEY = "team.request_lapse";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	private final ObjectProvider<TeamRequests> requests;

	TeamRequestLapse(ObjectProvider<TeamRequests> requests) {
		this.requests = requests;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.requests.getObject().lapse(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
