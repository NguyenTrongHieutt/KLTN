package vn.vibefix.product.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A GitHub repository address as a Customer pastes it: {@code https://github.com/{owner}/{name}},
 * with or without {@code www.}, the scheme, {@code .git}, a trailing slash, or a tail such as
 * {@code /tree/main}. Anything else is not a GitHub repository ({@code NOT_GITHUB}).
 */
record RepositoryUrl(String owner, String name) {

	private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	static Optional<RepositoryUrl> parse(String raw) {
		String text = raw == null ? "" : raw.strip();
		if (text.isEmpty() || text.length() > 300) {
			return Optional.empty();
		}
		if (!text.contains("://")) {
			text = "https://" + text;
		}
		URI uri;
		try {
			uri = new URI(text);
		}
		catch (URISyntaxException ex) {
			return Optional.empty();
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
		if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
				|| !(host.equals("github.com") || host.equals("www.github.com")) || uri.getPort() != -1
				|| uri.getUserInfo() != null || uri.getPath() == null) {
			return Optional.empty();
		}
		String[] segments = uri.getPath().replaceAll("^/+", "").split("/");
		if (segments.length < 2) {
			return Optional.empty();
		}
		String owner = segments[0];
		String name = segments[1].endsWith(".git") ? segments[1].substring(0, segments[1].length() - 4) : segments[1];
		if (!OWNER.matcher(owner).matches() || !NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
			return Optional.empty();
		}
		return Optional.of(new RepositoryUrl(owner, name));
	}

}
