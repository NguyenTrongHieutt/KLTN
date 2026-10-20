package vn.vibefix.work.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import vn.vibefix.shared.WorkItemKey;

/**
 * The Job code of a Board's keys (AD-33): the initials of the first three words of the Product name,
 * with Vietnamese marks removed and {@code đ} read as {@code d}, upper-cased ({@code Sổ đặt bàn Quán Mây}
 * gives {@code SDB}). A one-word name gives its first three letters, and a name with fewer than two
 * usable letters gives {@code JOB}. Within a Product a taken code gets the next free digit ({@code SDB2}).
 */
final class JobCodes {

	static final String FALLBACK = "JOB";

	private JobCodes() {
	}

	/** The code a Product name suggests, before the Product's other Boards are considered. */
	static String base(String productName) {
		String plain = Normalizer.normalize(productName.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toUpperCase(Locale.ROOT);
		List<String> words = new ArrayList<>();
		for (String word : plain.split("[^A-Z0-9]+")) {
			if (!word.isEmpty()) {
				words.add(word);
			}
		}
		StringBuilder initials = new StringBuilder();
		for (String word : words) {
			initials.append(word.charAt(0));
		}
		String code = fromFirstLetter(initials.toString());
		if (words.size() < 2 || code.length() < 2) {
			code = fromFirstLetter(String.join("", words));
		}
		code = code.length() > 3 ? code.substring(0, 3) : code;
		return WorkItemKey.CODE.matcher(code).matches() ? code : FALLBACK;
	}

	/** {@code base}, or {@code base} with the next digit from 2 when the Product already uses it. */
	static String unique(String base, Set<String> taken) {
		if (!taken.contains(base)) {
			return base;
		}
		for (int n = 2;; n++) {
			String candidate = base + n;
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
	}

	private static String fromFirstLetter(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (Character.isLetter(text.charAt(i))) {
				return text.substring(i);
			}
		}
		return "";
	}

}
