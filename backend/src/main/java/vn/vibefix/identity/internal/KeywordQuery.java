package vn.vibefix.identity.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A keyword as AD-36 searches it: {@code websearch_to_tsquery} over {@code platform.vi_search}, with a
 * prefix match on the last term, so "bao ma" already finds "Bảo mật". A last term holding anything
 * but letters and digits (a quote, a minus) is left to {@code websearch_to_tsquery} whole.
 */
record KeywordQuery(String condition, Map<String, Object> params) {

	/** Input stops at 100 characters (EXPERIENCE.md, Search bar). */
	static final int MAX_LENGTH = 100;

	private static final Pattern PLAIN = Pattern.compile("[\\p{L}\\p{N}]+");

	private static final String CONFIG = "'platform.vi_search'";

	/** The condition on {@code vector} for {@code raw}, or none for a blank keyword. */
	static Optional<KeywordQuery> of(String raw, String vector) {
		String text = raw.strip().replaceAll("\\s+", " ");
		if (text.isEmpty()) {
			return Optional.empty();
		}
		if (text.length() > MAX_LENGTH) {
			text = text.substring(0, MAX_LENGTH).strip();
		}
		String[] terms = text.split(" ");
		String last = terms[terms.length - 1];
		if (!PLAIN.matcher(last).matches()) {
			return Optional.of(new KeywordQuery(vector + " @@ websearch_to_tsquery(" + CONFIG + ", :keyword)",
					Map.of("keyword", text)));
		}
		String head = String.join(" ", Arrays.copyOf(terms, terms.length - 1));
		String prefix = "to_tsquery(" + CONFIG + ", :keywordLast || ':*')";
		if (head.isEmpty()) {
			return Optional.of(new KeywordQuery(vector + " @@ " + prefix, Map.of("keywordLast", last)));
		}
		return Optional.of(new KeywordQuery(
				vector + " @@ (websearch_to_tsquery(" + CONFIG + ", :keyword) && " + prefix + ")",
				Map.of("keyword", head, "keywordLast", last)));
	}

	/** The rank of a match, for ordering. */
	String rank(String vector) {
		return "ts_rank(" + vector + ", " + this.condition.substring(this.condition.indexOf("@@") + 3) + ")";
	}

}
