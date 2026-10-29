package vn.vibefix.shared;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The one rule for a link a person attaches (AD-21): a Work Item's link and a bid's proof (Story 21.2) are
 * {@code https}, with a host, without user info, spaces or control characters, and at most {@value #MAX_LENGTH}
 * characters; every screen shows a link with its domain. The owning module refuses a link this rule does not keep
 * with its own reason, {@code LINK_INVALID}.
 */
public final class HttpsLinks {

	/** The longest link kept. */
	public static final int MAX_LENGTH = 2000;

	private HttpsLinks() {
	}

	/** The link as stored, stripped, or empty when the rule does not keep it. */
	public static Optional<String> valid(@Nullable String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String url = raw.strip();
		if (url.isEmpty() || url.length() > MAX_LENGTH || url.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
			return Optional.empty();
		}
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException ex) {
			return Optional.empty();
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
				|| uri.getRawUserInfo() != null) {
			return Optional.empty();
		}
		return Optional.of(url);
	}

	/** The link's host in lower case, shown as its domain; empty if it cannot be read. */
	public static String domain(@Nullable String url) {
		if (url == null) {
			return "";
		}
		try {
			String host = new URI(url).getHost();
			return host == null ? "" : host.toLowerCase(Locale.ROOT);
		}
		catch (URISyntaxException ex) {
			return "";
		}
	}

}
