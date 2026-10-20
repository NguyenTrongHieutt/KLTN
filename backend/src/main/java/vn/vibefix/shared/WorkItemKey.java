package vn.vibefix.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Work Item's key, {@code {JOBCODE}-{n}} (AD-33): the Board's Job code (2 to 6 upper-case letters
 * or digits, starting with a letter) and the Work Item's number on its Job, counting from 1. This is
 * the only definition of the format; the Board, commit-message mentions, and the SPA's copy button
 * all go through it, for example {@code SDB-12}.
 */
public record WorkItemKey(String code, int number) {

	/** A Job code. */
	public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]{1,5}");

	/** A whole key; {@link #mentionedIn} finds keys inside text with the same pattern. */
	public static final Pattern PATTERN = Pattern.compile("(" + CODE.pattern() + ")-([1-9][0-9]{0,5})");

	private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9-])" + PATTERN.pattern() + "(?![0-9A-Za-z])");

	/** The highest number a key carries. */
	public static final int MAX_NUMBER = 999_999;

	public WorkItemKey {
		Objects.requireNonNull(code, "code");
		if (!CODE.matcher(code).matches()) {
			throw new IllegalArgumentException("A Job code is 2 to 6 upper-case letters or digits, starting with a letter: " + code);
		}
		if (number < 1 || number > MAX_NUMBER) {
			throw new IllegalArgumentException("A Work Item number is 1 to " + MAX_NUMBER + ": " + number);
		}
	}

	/** The key {@code text} spells exactly, or empty. */
	public static Optional<WorkItemKey> parse(String text) {
		Matcher matcher = PATTERN.matcher(text);
		return matcher.matches() ? Optional.of(new WorkItemKey(matcher.group(1), Integer.parseInt(matcher.group(2))))
				: Optional.empty();
	}

	/** Every key mentioned in {@code text}, in order, for example in a commit message. */
	public static List<WorkItemKey> mentionedIn(String text) {
		List<WorkItemKey> keys = new ArrayList<>();
		Matcher matcher = MENTION.matcher(text);
		while (matcher.find()) {
			keys.add(new WorkItemKey(matcher.group(1), Integer.parseInt(matcher.group(2))));
		}
		return keys;
	}

	@Override
	public String toString() {
		return this.code + "-" + this.number;
	}

}
