package vn.vibefix.authz;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the fixtures built for one matrix row: the named values ({@code jobId}, {@code noteId}, ...)
 * that fill the row's path and body placeholders. A fresh world is built for every row, on a
 * database restored from its template.
 */
public final class MatrixWorld {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

	private final Map<String, String> values = new HashMap<>();

	public void put(String name, Object value) {
		if (this.values.putIfAbsent(name, String.valueOf(value)) != null) {
			throw new IllegalStateException("Two fixtures seed the value " + name);
		}
	}

	public String get(String name) {
		String value = this.values.get(name);
		if (value == null) {
			throw new AssertionError("No fixture seeds {" + name + "}");
		}
		return value;
	}

	/** {@code template} with every {@code {name}} replaced by its seeded value. */
	public String expand(String template) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder expanded = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(expanded, Matcher.quoteReplacement(get(matcher.group(1))));
		}
		matcher.appendTail(expanded);
		return expanded.toString();
	}

}
