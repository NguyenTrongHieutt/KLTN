package vn.vibefix.shared;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A keyword as AD-36 searches it, defined once for every keyword search: {@code websearch_to_tsquery}
 * over {@code platform.vi_search}, with a prefix match on the last term, so "bao ma" already finds
 * "Bảo mật" (SG-39). The last term is reduced to its letters and digits; the prefix is left out when
 * the input ends in a quote, the last term is a negation ({@code -x}) or {@code or}, or nothing is
 * left of it. An input that holds no word matches nothing, never an error.
 * @param condition the SQL condition on the vector, with named parameters
 */
public record KeywordQuery(String condition, Map<String, Object> params) {

	/** Input stops at 100 characters (EXPERIENCE.md, Search bar). */
	public static final int MAX_LENGTH = 100;

	private static final String CONFIG = "'platform.vi_search'";

	public KeywordQuery {
		params = Map.copyOf(params);
	}

	/** The condition on {@code vector} for {@code raw}, or none for a blank keyword. */
	public static Optional<KeywordQuery> of(String raw, String vector) {
		String text = raw.strip().replaceAll("\\s+", " ");
		if (text.isEmpty()) {
			return Optional.empty();
		}
		if (text.length() > MAX_LENGTH) {
			text = text.substring(0, MAX_LENGTH).strip();
		}
		String[] terms = text.split(" ");
		String last = terms[terms.length - 1];
		String reduced = last.replaceAll("[^\\p{L}\\p{N}]", "");
		boolean noPrefix = text.endsWith("\"") || last.contains("\"") || last.startsWith("-")
				|| last.toLowerCase(Locale.ROOT).equals("or") || reduced.isEmpty();
		if (noPrefix) {
			return Optional.of(new KeywordQuery(vector + " @@ websearch_to_tsquery(" + CONFIG + ", :keyword)",
					Map.of("keyword", text)));
		}
		String head = String.join(" ", Arrays.copyOf(terms, terms.length - 1));
		String prefix = "to_tsquery(" + CONFIG + ", :keywordLast || ':*')";
		if (head.isEmpty()) {
			return Optional.of(new KeywordQuery(vector + " @@ " + prefix, Map.of("keywordLast", reduced)));
		}
		return Optional.of(new KeywordQuery(
				vector + " @@ (websearch_to_tsquery(" + CONFIG + ", :keyword) && " + prefix + ")",
				Map.of("keyword", head, "keywordLast", reduced)));
	}

	/** The rank of a match, for ordering. */
	public String rank(String vector) {
		return "ts_rank(" + vector + ", " + this.condition.substring(this.condition.indexOf("@@") + 3) + ")";
	}

}
