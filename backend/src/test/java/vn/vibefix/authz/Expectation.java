package vn.vibefix.authz;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The {@code expect} column of a matrix row: {@code status [CODE [REASON]]}, for example
 * {@code 200}, {@code 401}, {@code 404 NOT_FOUND} or {@code 403 FORBIDDEN GITHUB_NOT_LINKED}.
 */
public record Expectation(int status, @Nullable String code, @Nullable String reason) {

	public Expectation {
		if (status < 100 || status > 599) {
			throw new IllegalArgumentException("Not an HTTP status: " + status);
		}
		if (code == null && reason != null) {
			throw new IllegalArgumentException("A reason needs a code");
		}
	}

	public static Expectation parse(String text) {
		String[] parts = Objects.requireNonNull(text, "expect").trim().split("\\s+");
		if (parts.length == 0 || parts.length > 3 || parts[0].isEmpty()) {
			throw new IllegalArgumentException("expect must be 'status [CODE [REASON]]', not '" + text + "'");
		}
		try {
			return new Expectation(Integer.parseInt(parts[0]), parts.length > 1 ? parts[1] : null,
					parts.length > 2 ? parts[2] : null);
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("expect must start with an HTTP status, not '" + text + "'", ex);
		}
	}

	@Override
	public String toString() {
		return this.status + (this.code == null ? "" : " " + this.code) + (this.reason == null ? "" : " " + this.reason);
	}

}
