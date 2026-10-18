package vn.vibefix.shared;

import java.util.Optional;

/**
 * The single set of error codes the API returns in the {@code code} property of an RFC 9457
 * Problem Details response (AD-23). The SPA maps each code, and the optional module
 * {@code reason}, to Vietnamese; the backend never returns user-facing sentences.
 */
public enum ErrorCode {

	VALIDATION(400),
	NOT_FOUND(404),
	FORBIDDEN(403),
	STALE(409),
	JOB_BUSY(409),
	GUARD_FAILED(422),
	RATE_LIMITED(429),
	AI_CONSENT_REQUIRED(403),
	AI_UNAVAILABLE(503),
	CONFLICT(409);

	private final int status;

	ErrorCode(int status) {
		this.status = status;
	}

	/** The HTTP status a response carrying this code uses. */
	public int status() {
		return this.status;
	}

	/**
	 * The code for an error Spring raised itself (no {@link ApiException}), derived from its
	 * status: 403 {@code FORBIDDEN}, 404 {@code NOT_FOUND}, 409 {@code CONFLICT}, 429
	 * {@code RATE_LIMITED}, and every other 4xx (400, 405, 406, 413, 415, ...) {@code VALIDATION}.
	 * Server errors (5xx) carry no code.
	 */
	public static Optional<ErrorCode> forStatus(int status) {
		if (status < 400 || status >= 500) {
			return Optional.empty();
		}
		return Optional.of(switch (status) {
			case 403 -> FORBIDDEN;
			case 404 -> NOT_FOUND;
			case 409 -> CONFLICT;
			case 429 -> RATE_LIMITED;
			default -> VALIDATION;
		});
	}

}
