package vn.vibefix.shared;

import java.util.List;

/**
 * FR-62 at its counts-only level (SM-C2): one module's figures for ADM-9. Each module that owns a
 * figure implements it over its own tables; {@code admin} shows every group. No figure names a person.
 */
public interface PlatformCounts {

	/** One figure: its group (a table on ADM-9), its key within the group, and the value. */
	record Count(String group, String key, long value) {
	}

	List<Count> counts();

}
