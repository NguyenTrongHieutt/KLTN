package vn.vibefix.notification.internal;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;

/**
 * A Vietnamese email template (AD-27): one UTF-8 file per notification type,
 * {@code templates/email/{type}.txt}, with a {@code subject:} line, an optional {@code link:} line
 * naming a platform path, then {@code ---} and the body. Placeholders are {@code {name}}, as in
 * {@code vi.json}.
 * <p>
 * Every value is cleaned of control and direction-override characters, so it can add no header or
 * line and cannot reorder text; the HTML part escapes the whole text after the values are filled in
 * (AD-21). There is no way to insert unescaped HTML.
 */
final class EmailTemplate {

	static final String FOOTER = "Email này được vibefix gửi tự động. Bạn không cần trả lời email này.";

	static final String LINK_LABEL = "Mở vibefix";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)\\}");

	private static final Pattern PATH = Pattern.compile("/[a-z0-9\\-/]*(\\?[a-z0-9-]+=[a-z0-9-]+)?");

	/** Unicode direction marks and overrides, which could make an address or code read differently. */
	private static final Pattern DIRECTION_CONTROLS = Pattern.compile("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

	private final String type;

	private final String subject;

	private final @Nullable String link;

	private final String body;

	private final Set<String> placeholders;

	private EmailTemplate(String type, String subject, @Nullable String link, String body) {
		this.type = type;
		this.subject = subject;
		this.link = link;
		this.body = body;
		Set<String> names = new LinkedHashSet<>();
		collect(subject, names);
		collect(body, names);
		this.placeholders = Set.copyOf(names);
	}

	/** Parses a template file's text. */
	static EmailTemplate parse(String type, String source) {
		// A leading line break lets the separator be found on the first line too.
		String text = "\n" + source.replace("\r\n", "\n").replace("\r", "\n");
		int separator = text.indexOf("\n---\n");
		if (separator < 0) {
			throw new IllegalStateException("Email template " + type + " has no '---' line between headers and body");
		}
		Map<String, String> headers = new LinkedHashMap<>();
		for (String line : text.substring(0, separator).split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			int colon = line.indexOf(':');
			String name = colon < 0 ? line.strip() : line.substring(0, colon).strip();
			if (colon < 0 || !List.of("subject", "link").contains(name) || headers.containsKey(name)) {
				throw new IllegalStateException("Email template " + type + " has an unknown or repeated header: " + line);
			}
			headers.put(name, line.substring(colon + 1).strip());
		}
		String subject = headers.get("subject");
		if (subject == null || subject.isEmpty()) {
			throw new IllegalStateException("Email template " + type + " has no subject");
		}
		String link = headers.get("link");
		if (link != null && !PATH.matcher(link).matches()) {
			throw new IllegalStateException("Email template " + type + " links to '" + link + "', not a platform path");
		}
		String body = text.substring(separator + "\n---\n".length()).strip();
		if (body.isEmpty()) {
			throw new IllegalStateException("Email template " + type + " has no body");
		}
		return new EmailTemplate(type, subject, link, body);
	}

	Set<String> placeholders() {
		return this.placeholders;
	}

	/** Fails when a placeholder has no value: a request is checked before it is recorded. */
	void requireValues(Map<String, String> values) {
		for (String name : this.placeholders) {
			if (values.get(name) == null) {
				throw new IllegalArgumentException("Email template " + this.type + " needs a value for {" + name + "}");
			}
		}
	}

	/**
	 * The message for {@code values}, linking where the template says.
	 * @param linkBaseUrl the platform's address, before a {@code link:} path
	 */
	RenderedEmail render(Map<String, String> values, String linkBaseUrl) {
		return render(values, linkBaseUrl, null);
	}

	/**
	 * The message for {@code values}.
	 * @param link a platform path to link to in place of the template's {@code link:}, or {@code null}
	 */
	RenderedEmail render(Map<String, String> values, String linkBaseUrl, @Nullable String link) {
		requireValues(values);
		if (link != null && !PATH.matcher(link).matches()) {
			throw new IllegalArgumentException("An email links to a platform path, not '" + link + "'");
		}
		String subject = fill(this.subject, values).replace('\n', ' ');
		String body = fill(this.body, values);
		String path = link != null ? link : this.link;
		String url = path == null ? null : stripTrailingSlash(linkBaseUrl) + path;

		StringBuilder text = new StringBuilder(body);
		if (url != null) {
			text.append("\n\n").append(LINK_LABEL).append(": ").append(url);
		}
		text.append("\n\n-- \n").append(FOOTER).append('\n');

		StringBuilder html = new StringBuilder();
		html.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"utf-8\">\n<title>")
			.append(escape(subject))
			.append("</title>\n</head>\n<body style=\"margin:0;padding:24px;font-family:Arial,Helvetica,sans-serif;")
			.append("font-size:16px;line-height:1.6;color:#1f2933;background:#ffffff\">\n");
		for (String paragraph : body.split("\n\\s*\n")) {
			html.append("<p>").append(escape(paragraph.strip()).replace("\n", "<br>\n")).append("</p>\n");
		}
		if (url != null) {
			html.append("<p><a href=\"")
				.append(escape(url))
				.append("\" style=\"display:inline-block;padding:10px 16px;border-radius:6px;background:#1d4ed8;")
				.append("color:#ffffff;text-decoration:none;font-weight:600\">")
				.append(escape(LINK_LABEL))
				.append("</a></p>\n");
		}
		html.append("<p style=\"margin-top:32px;font-size:13px;color:#52606d\">")
			.append(escape(FOOTER))
			.append("</p>\n</body>\n</html>\n");
		return new RenderedEmail(subject, text.toString(), html.toString());
	}

	/** Strips control characters (line breaks included) and direction overrides from a value. */
	static String clean(String value) {
		StringBuilder cleaned = new StringBuilder(value.length());
		value.codePoints().filter(point -> !Character.isISOControl(point)).forEach(cleaned::appendCodePoint);
		return DIRECTION_CONTROLS.matcher(cleaned).replaceAll("");
	}

	private static String fill(String template, Map<String, String> values) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder filled = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(filled, Matcher.quoteReplacement(clean(values.get(matcher.group(1)))));
		}
		matcher.appendTail(filled);
		return filled.toString();
	}

	private static void collect(String template, Set<String> names) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
	}

	private static String escape(String text) {
		return HtmlUtils.htmlEscape(text, "UTF-8");
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** The parts of one message. */
	record RenderedEmail(String subject, String text, String html) {
	}

}
