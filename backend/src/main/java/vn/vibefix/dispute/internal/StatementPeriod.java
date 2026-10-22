package vn.vibefix.dispute.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.notification.Todos;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value #KEY} (FR-52, AD-7): the other side's statement period, non-pausable, since the Dispute's own
 * freeze must not stop it. When it ends on an open Dispute the statement to-dos close and a decision no
 * longer waits; a decided Dispute leaves it {@code OBSOLETE}. Collaborators are looked up on use.
 */
@Component
class StatementPeriod implements DeadlineHandler {

	static final String KEY = "dispute.statement_period";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private final ObjectProvider<Disputes> disputes;

	private final ObjectProvider<Todos> todos;

	StatementPeriod(ObjectProvider<Disputes> disputes, ObjectProvider<Todos> todos) {
		this.disputes = disputes;
		this.todos = todos;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	@Transactional
	public DeadlineOutcome handle(DueDeadline deadline) {
		boolean open = this.disputes.getObject()
			.find(deadline.subjectId())
			.filter(dispute -> "OPEN".equals(dispute.status()))
			.isPresent();
		if (!open) {
			return DeadlineOutcome.OBSOLETE;
		}
		this.todos.getObject().closeAll(Disputes.SUBMIT_STATEMENT, deadline.subjectId());
		return DeadlineOutcome.FIRED;
	}

}
