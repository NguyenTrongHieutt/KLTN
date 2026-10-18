package vn.vibefix.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * An {@link OperationHandler} for tests (type {@value #TYPE}, lane {@code GITHUB_SHORT}): each run
 * answers the next scripted outcome, then {@link Outcome#succeeded()}; each read-back the next
 * scripted answer, then nothing. It records what it was asked, never acting on anything.
 */
public class ScriptedOperationHandler implements OperationHandler {

	public static final String TYPE = "test.scripted";

	private final Deque<Outcome> outcomes = new ArrayDeque<>();

	private final Deque<Optional<Map<String, Object>>> readBacks = new ArrayDeque<>();

	private final List<OperationRun> runs = new ArrayList<>();

	private final List<OperationRun> readBackRuns = new ArrayList<>();

	private final List<String> threads = new ArrayList<>();

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.GITHUB_SHORT;
	}

	@Override
	public synchronized Outcome run(OperationRun run) {
		this.runs.add(run);
		this.threads.add(Thread.currentThread().getName());
		Outcome next = this.outcomes.poll();
		return next == null ? Outcome.succeeded() : next;
	}

	@Override
	public synchronized Optional<Map<String, Object>> readBack(OperationRun run) {
		this.readBackRuns.add(run);
		Optional<Map<String, Object>> next = this.readBacks.poll();
		return next == null ? Optional.empty() : next;
	}

	public synchronized ScriptedOperationHandler answer(Outcome... outcomes) {
		this.outcomes.addAll(List.of(outcomes));
		return this;
	}

	public synchronized ScriptedOperationHandler readBackFinds(Map<String, Object> result) {
		this.readBacks.add(Optional.of(result));
		return this;
	}

	public synchronized List<OperationRun> runs() {
		return List.copyOf(this.runs);
	}

	/** The name of the thread each run ran on. */
	public synchronized List<String> threads() {
		return List.copyOf(this.threads);
	}

	public synchronized List<OperationRun> readBacks() {
		return List.copyOf(this.readBackRuns);
	}

	public synchronized void reset() {
		this.outcomes.clear();
		this.readBacks.clear();
		this.runs.clear();
		this.readBackRuns.clear();
		this.threads.clear();
	}

}
