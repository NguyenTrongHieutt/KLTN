package vn.vibefix.scanning;

import org.jspecify.annotations.Nullable;

/**
 * The commits a scan reads: from {@code base} (excluded) to {@code head}, or the whole history of
 * every ref when both are {@code null}.
 */
public record ShaRange(@Nullable String base, @Nullable String head) {

	private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("[0-9a-f]{40}");

	public ShaRange {
		if (base != null && !SHA.matcher(base).matches() || head != null && !SHA.matcher(head).matches()) {
			throw new IllegalArgumentException("A commit is 40 lower-case hexadecimal characters");
		}
		if (base != null && head == null) {
			throw new IllegalArgumentException("A range with a base needs a head");
		}
	}

	public static ShaRange wholeHistory() {
		return new ShaRange(null, null);
	}

	public boolean isWholeHistory() {
		return this.base == null && this.head == null;
	}

}
