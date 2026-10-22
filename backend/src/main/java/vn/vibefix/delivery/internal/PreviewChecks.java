package vn.vibefix.delivery.internal;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Whether a handover's preview link responds (FR-32, AD-35), through {@value PreviewCheckJobHandler#TYPE}
 * outside any transaction. Tests answer it with a scripted stand-in, since no test reaches the internet.
 */
interface PreviewChecks {

	/**
	 * @param domain the link's host, as screens show it
	 * @param reason why it did not respond, one of {@link PreviewCheckJobHandler}'s codes
	 */
	record Result(boolean reachable, String domain, @Nullable String reason) {
	}

	Result check(String url);

	/** The host of an {@code https} link in lower case and Unicode, or the link itself when it has none. */
	static String domain(String url) {
		try {
			String host = new URI(url.strip()).getHost();
			return host == null ? url : IDN.toUnicode(host).toLowerCase(Locale.ROOT);
		}
		catch (Exception ex) {
			return url;
		}
	}

}
