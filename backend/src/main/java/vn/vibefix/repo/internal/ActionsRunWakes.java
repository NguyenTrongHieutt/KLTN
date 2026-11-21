package vn.vibefix.repo.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.github.ActionsRunUpdated;
import vn.vibefix.github.ActionsRuns;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationTicket;
import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.DueDeadline;

/**
 * What wakes an operation that dispatched a GitHub Actions run (AD-46, Story 24.5a): {@value JobSetups#OPERATION}'s
 * copy, {@value JobHandoverCopies#TYPE} and {@value FinalMerges#MERGE}. After its dispatch each one defers to its
 * backstop alone, the correlation's end of life, and nothing reads its run on an interval. Two things wake it, and it
 * then reads the run and the repositories from the API before it acts:
 * <ul>
 * <li>{@link ActionsRunUpdated}: the run completed, as the App that dispatched it delivered it, or its path report
 * or the exchange's refusal of it arrived;</li>
 * <li>{@value #KEY}, the last resort: one Deadline per dispatched run, keyed by its correlation and due
 * {@link ActionsRuns#RUN_TIMEOUT} after the dispatch. The operation woken past that time reads the run once: a
 * completed run resolves as if its event had come, and any other takes the timeout path.</li>
 * </ul>
 * Either wakes the operation only while it still waits on that correlation: the row is running under it and has read
 * no conclusion of its run. Otherwise the event changes nothing and the Deadline is {@code OBSOLETE}, since
 * {@code timers} cancels nothing. Not pausable: a frozen Job's operation only waits when it wakes (AD-28), and a run's
 * timeout runs out all the same. Collaborators are looked up on use.
 */
@Component
class ActionsRunWakes implements DeadlineHandler {

	static final String KEY = "repo.actions_run_timeout";

	static final DeadlineKind KIND = new DeadlineKind(KEY, false, true);

	private static final Logger log = LoggerFactory.getLogger(ActionsRunWakes.class);

	private final ObjectProvider<JobSetups> setups;

	private final ObjectProvider<JobHandoverCopies> copies;

	private final ObjectProvider<FinalMerges> merges;

	private final ObjectProvider<ExternalOperations> operations;

	ActionsRunWakes(ObjectProvider<JobSetups> setups, ObjectProvider<JobHandoverCopies> copies,
			ObjectProvider<FinalMerges> merges, ObjectProvider<ExternalOperations> operations) {
		this.setups = setups;
		this.copies = copies;
		this.merges = merges;
		this.operations = operations;
	}

	/**
	 * The one {@value #KEY} Deadline of a run dispatched at {@code dispatchedAt}, which the dispatch records in the
	 * transaction that names the correlation as the row's run.
	 * @param type the operation that dispatched it, the Deadline's generation
	 */
	static DeadlineRequest timeout(String type, UUID correlation, UUID jobId, Instant dispatchedAt) {
		return new DeadlineRequest(KEY, correlation, type, jobId, dispatchedAt.plus(ActionsRuns.RUN_TIMEOUT));
	}

	/** The backstop of a run dispatched at {@code dispatchedAt}: its correlation's end of life. */
	static Instant backstop(Instant dispatchedAt) {
		return dispatchedAt.plus(ActionsRuns.CORRELATION_LIFETIME);
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return wake(deadline.generation(), deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

	/** The run's hint: its operation reads it now. Replay-safe, since a second wake only makes it due again. */
	@ApplicationModuleListener
	void onRunUpdated(ActionsRunUpdated event) {
		wake(null, event.correlationId());
	}

	/**
	 * Wakes the operation that waits on {@code correlation}.
	 * @param type the operation's type, when the caller knows it; any of the three otherwise
	 * @return whether one waited and was woken
	 */
	boolean wake(@Nullable String type, UUID correlation) {
		String key = waiting(type, correlation).orElse(null);
		if (key == null) {
			return false;
		}
		OperationTicket ticket = this.operations.getObject().find(key).orElse(null);
		if (ticket == null || !this.operations.getObject().wake(ticket.id())) {
			return false;
		}
		log.info("Operation {} woken for the run of correlation {}", key, correlation);
		return true;
	}

	/** The key of the operation whose row runs under {@code correlation} and has read no conclusion of its run. */
	private Optional<String> waiting(@Nullable String type, UUID correlation) {
		if (type == null || JobSetups.OPERATION.equals(type)) {
			Optional<String> setup = this.setups.getObject().waitingOn(correlation);
			if (setup.isPresent() || type != null) {
				return setup;
			}
		}
		if (type == null || JobHandoverCopies.TYPE.equals(type)) {
			Optional<String> copy = this.copies.getObject().waitingOn(correlation);
			if (copy.isPresent() || type != null) {
				return copy;
			}
		}
		if (type == null || FinalMerges.MERGE.equals(type)) {
			return this.merges.getObject().waitingOn(correlation);
		}
		return Optional.empty();
	}

}
