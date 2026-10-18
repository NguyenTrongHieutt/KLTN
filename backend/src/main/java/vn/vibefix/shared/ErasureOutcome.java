package vn.vibefix.shared;

import java.util.List;

/** The result of an erasure through {@link PersonalData#erase} (AD-24). */
public sealed interface ErasureOutcome {

	/** Every participant erased the account. */
	record Erased() implements ErasureOutcome {
	}

	/** Nothing was erased, because of these blockers; the deletion request waits. */
	record Blocked(List<ErasureBlocker> blockers) implements ErasureOutcome {

		public Blocked {
			blockers = List.copyOf(blockers);
			if (blockers.isEmpty()) {
				throw new IllegalArgumentException("A blocked erasure names its blockers");
			}
		}

	}

}
