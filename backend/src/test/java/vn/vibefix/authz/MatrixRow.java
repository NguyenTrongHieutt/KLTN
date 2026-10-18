package vn.vibefix.authz;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One row of {@code src/test/resources/authz/matrix.csv} (AD-20): an endpoint, a caller, the
 * caller's state, an optional request body, and the expected answer.
 * <p>
 * The file has the header {@code endpoint,caller,subject_state,body,expect}; lines starting with
 * {@code #} and blank lines are ignored. Fields hold no commas.
 * <ul>
 * <li>{@code endpoint}: {@code METHOD /path}, where {@code {name}} is a path variable a fixture
 * seeds, for example {@code GET /api/v1/jobs/{jobId}}.</li>
 * <li>{@code caller}: a {@link MatrixCaller}.</li>
 * <li>{@code subject_state}: {@code key=value} pairs separated by {@code ;} (for example
 * {@code locked=true;mentor=PENDING}); the fixture that realizes the caller must understand every
 * key.</li>
 * <li>{@code body}: the name of a JSON file under {@code authz/bodies/}, with the same
 * {@code {name}} placeholders.</li>
 * <li>{@code expect}: an {@link Expectation}.</li>
 * </ul>
 */
public record MatrixRow(HttpMethod method, String path, MatrixCaller caller, Map<String, String> state,
		@Nullable String body, Expectation expect) {

	public static final String RESOURCE = "authz/matrix.csv";

	static final List<String> HEADER = List.of("endpoint", "caller", "subject_state", "body", "expect");

	private static final Set<HttpMethod> METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
			HttpMethod.PATCH, HttpMethod.DELETE);

	public MatrixRow {
		state = Map.copyOf(state);
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("A path starts with '/': " + path);
		}
	}

	/** {@code METHOD /path}, the form the coverage check compares with the handlers. */
	public String endpoint() {
		return this.method.name() + " " + this.path;
	}

	@Override
	public String toString() {
		return endpoint() + " as " + this.caller + (this.state.isEmpty() ? "" : " " + this.state) + " -> "
				+ this.expect;
	}

	/** Every row of the matrix file. */
	public static List<MatrixRow> loadMatrix() {
		try (InputStream in = MatrixRow.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing");
			}
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static List<MatrixRow> parse(String csv) {
		List<MatrixRow> rows = new ArrayList<>();
		boolean headerSeen = false;
		int number = 0;
		for (String line : csv.lines().toList()) {
			number++;
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			List<String> fields = List.of(trimmed.split(",", -1)).stream().map(String::strip).toList();
			if (!headerSeen) {
				if (!fields.equals(HEADER)) {
					throw new IllegalArgumentException("The matrix header must be " + String.join(",", HEADER));
				}
				headerSeen = true;
				continue;
			}
			try {
				rows.add(row(fields));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("matrix line " + number + ": " + ex.getMessage(), ex);
			}
		}
		if (!headerSeen) {
			throw new IllegalArgumentException("The matrix has no header");
		}
		return rows;
	}

	private static MatrixRow row(List<String> fields) {
		if (fields.size() != HEADER.size()) {
			throw new IllegalArgumentException("expected " + HEADER.size() + " fields, found " + fields.size());
		}
		String[] endpoint = fields.get(0).split("\\s+", 2);
		if (endpoint.length != 2) {
			throw new IllegalArgumentException("endpoint must be 'METHOD /path', not '" + fields.get(0) + "'");
		}
		HttpMethod method = HttpMethod.valueOf(endpoint[0]);
		if (!METHODS.contains(method)) {
			throw new IllegalArgumentException("unsupported method " + method);
		}
		return new MatrixRow(method, endpoint[1], MatrixCaller.valueOf(fields.get(1)), state(fields.get(2)),
				fields.get(3).isEmpty() ? null : fields.get(3), Expectation.parse(fields.get(4)));
	}

	private static Map<String, String> state(String text) {
		Map<String, String> state = new LinkedHashMap<>();
		if (text.isEmpty()) {
			return state;
		}
		for (String pair : text.split(";")) {
			String[] keyValue = pair.split("=", 2);
			if (keyValue.length != 2 || keyValue[0].isBlank()) {
				throw new IllegalArgumentException("subject_state must be key=value;..., not '" + text + "'");
			}
			if (state.put(keyValue[0].strip(), keyValue[1].strip()) != null) {
				throw new IllegalArgumentException("subject_state repeats " + keyValue[0]);
			}
		}
		return state;
	}

}
