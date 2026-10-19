package vn.vibefix.identity.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-74, AD-19): 30 days after a rejection the LinkedIn profile is free again, as the
 * Mentor may submit again from then. A resubmission or a later decision leaves it {@code OBSOLETE}.
 */
@Component
class MentorReapplyWait implements DeadlineHandler {

	static final String KEY = "identity.mentor_reapply_wait";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, false);

	private final JdbcClient jdbc;

	MentorReapplyWait(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		boolean rejected = MentorDecisions.currentDecision(this.jdbc, deadline.subjectId(), deadline.generation())
			.filter("REJECTED"::equals)
			.isPresent();
		if (!rejected) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.jdbc.sql("UPDATE identity.mentor_application SET linkedin_reserved = FALSE WHERE account_id = :id")
			.param("id", deadline.subjectId())
			.update();
		return DeadlineOutcome.FIRED;
	}

}
