package vn.vibefix.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * A test Deadline kind (AD-7) whose handler answers what the test scripts, in order (then
 * {@code FIRED}), records every Deadline it handled, and throws when scripted to.
 */
public class ScriptedDeadlineHandler implements DeadlineHandler {

	/** A pausable kind, which belongs to a Job. */
	public static final DeadlineKind PAUSABLE = new DeadlineKind("test.pausable", true, true);

	/** A kind that is neither pausable nor job-scoped. */
	public static final DeadlineKind PLAIN = new DeadlineKind("test.plain", false, false);

	private final DeadlineKind kind;

	private final Deque<Object> answers = new ArrayDeque<>();

	private final List<DueDeadline> handled = new ArrayList<>();

	private long pauseMillis;

	public ScriptedDeadlineHandler(DeadlineKind kind) {
		this.kind = kind;
	}

	@Override
	public DeadlineKind kind() {
		return this.kind;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		long pause;
		Object next;
		synchronized (this) {
			this.handled.add(deadline);
			next = this.answers.poll();
			pause = this.pauseMillis;
		}
		if (pause > 0) {
			try {
				Thread.sleep(pause);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		if (next instanceof RuntimeException failure) {
			throw failure;
		}
		return next == null ? DeadlineOutcome.FIRED : (DeadlineOutcome) next;
	}

	public synchronized ScriptedDeadlineHandler answer(DeadlineOutcome... outcomes) {
		this.answers.addAll(List.of(outcomes));
		return this;
	}

	public synchronized ScriptedDeadlineHandler fail(RuntimeException failure) {
		this.answers.add(failure);
		return this;
	}

	/** Makes each handling take this long, so two pollers overlap. */
	public synchronized ScriptedDeadlineHandler taking(long millis) {
		this.pauseMillis = millis;
		return this;
	}

	public synchronized List<DueDeadline> handled() {
		return List.copyOf(this.handled);
	}

	public synchronized void reset() {
		this.answers.clear();
		this.handled.clear();
		this.pauseMillis = 0;
	}

}
