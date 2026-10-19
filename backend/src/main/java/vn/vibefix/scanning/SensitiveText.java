package vn.vibefix.scanning;

import java.util.List;

/**
 * FR-47's check of text about to be sent (AD-18): the same {@code scanning/rules.yaml} rules as the
 * Secret Scan, with no AI, on at most {@link #MAX_LENGTH} characters. The Job Chat and the Crew Chat
 * warn before sending what it finds; the raw values never leave the check.
 */
public interface SensitiveText {

	/** The input-length cap: no chat message is longer. */
	int MAX_LENGTH = 4000;

	enum Kind {

		/** A likely credential (a credential rule matched). */
		CREDENTIAL,

		/** Personal data in bulk (the Personal-Data Check's thresholds were met). */
		PERSONAL_DATA

	}

	/**
	 * One finding.
	 * @param type the rule's type for a credential, for example "Khóa Supabase"; the kind of value for
	 * personal data
	 * @param masked the value as FR-8 masks it
	 */
	record Match(Kind kind, String type, String masked) {
	}

	/**
	 * What the check found.
	 * @param maskedText the text with every match replaced by its mask
	 */
	record Result(List<Match> matches, String maskedText) {

		public Result {
			matches = List.copyOf(matches);
		}

		public boolean found() {
			return !this.matches.isEmpty();
		}

		public long count(Kind kind) {
			return this.matches.stream().filter(match -> match.kind() == kind).count();
		}

	}

	/** Checks the first {@link #MAX_LENGTH} characters of {@code text}. */
	Result check(String text);

}
