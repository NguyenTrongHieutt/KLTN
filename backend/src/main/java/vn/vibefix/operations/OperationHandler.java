package vn.vibefix.operations;

import java.util.Map;
import java.util.Optional;

/**
 * Runs one type of External Operation (AD-6), outside any transaction. Upper modules implement it
 * for their own types (AD-3 port); only this module's worker calls it.
 * <p>
 * {@link #run} re-checks the platform precondition and answers {@link Outcome.Obsolete} when it no
 * longer holds, then reads the outside state, acts only if needed, and verifies by reading back. It
 * classifies failures instead of throwing: an exception counts as {@link Outcome.Transient}.
 */
public interface OperationHandler {

	/** The type this handler runs, as {@code module.name}; also the first part of every key. */
	String type();

	Lane lane();

	default OperationPolicy policy() {
		return OperationPolicy.DEFAULT;
	}

	Outcome run(OperationRun run);

	/**
	 * Whether the effect already happened, read from the outside system: before the attempt after an
	 * ambiguous one or an expired lease, and before {@link OperationStatus#FAILED}. Empty when it did
	 * not happen or cannot be read back (email: at least once).
	 */
	default Optional<Map<String, Object>> readBack(OperationRun run) {
		return Optional.empty();
	}

}
