package vn.vibefix.files.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Stored file names (AD-21): only the last path part, NFC, no control or format characters,
 * whitespace collapsed, and at most {@value #MAX_LENGTH} characters with the stored type's extension.
 * {@code Content-Disposition} encodes them per RFC 6266 when they are served.
 */
final class FileNames {

	static final int MAX_LENGTH = 200;

	static final String FALLBACK = "tep";

	private FileNames() {
	}

	/** {@code original} cleaned, ending in {@code .extension}. */
	static String normalize(String original, String extension) {
		String name = Normalizer.normalize(original, Normalizer.Form.NFC);
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		// Whitespace first, so a tab between words still separates them.
		name = name.replaceAll("\\s+", " ").replaceAll("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]", "").replaceAll(" +", " ").strip();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		base = base.replaceAll("^[.\\s]+|[.\\s]+$", "");
		if (base.isEmpty()) {
			base = FALLBACK;
		}
		String suffix = "." + extension;
		int room = MAX_LENGTH - suffix.length();
		if (base.codePointCount(0, base.length()) > room) {
			base = base.substring(0, base.offsetByCodePoints(0, room)).strip();
		}
		return base + suffix;
	}

	/**
	 * {@code attachment} with both names RFC 6266 describes: an ASCII {@code filename} for old
	 * clients (accents dropped, anything else non-ASCII, quotes, and backslashes as {@code _}), and
	 * {@code filename*} in UTF-8, percent-encoded (RFC 5987).
	 */
	static String contentDisposition(String name) {
		String ascii = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.replaceAll("[^\\x20-\\x7E]|[\"\\\\]", "_");
		String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
		return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
	}

}
