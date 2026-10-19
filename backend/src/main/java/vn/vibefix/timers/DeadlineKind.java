package vn.vibefix.timers;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A kind of Deadline (AD-7 Kinds): its key ({@code {owning module}.{rule}}), whether a Job freeze pauses
 * it, and whether it belongs to a Job. A pausable kind belongs to a Job, since only Jobs freeze.
 */
public record DeadlineKind(String key, boolean pausable, boolean jobScoped) {

	private static final Pattern KEY = Pattern.compile("[a-z]+(\\.[a-z0-9_]+)+");

	public DeadlineKind {
		Objects.requireNonNull(key, "key");
		if (!KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("A Deadline kind looks like module.rule, not '" + key + "'");
		}
		if (pausable && !jobScoped) {
			throw new IllegalArgumentException("A pausable Deadline kind belongs to a Job: " + key);
		}
	}

	/** The owning module, the key's first part. */
	public String owner() {
		return this.key.substring(0, this.key.indexOf('.'));
	}

}
