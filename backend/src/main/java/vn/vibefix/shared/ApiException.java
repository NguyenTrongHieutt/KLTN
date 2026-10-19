package vn.vibefix.shared;

import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * A refusal the API reports as Problem Details with {@code code} and, when given, {@code reason}
 * (AD-23). The reason is a constant of the throwing module's own reason enum (for example
 * {@code REPO_PRIVATE}); the SPA maps it to Vietnamese.
 * <p>
 * The message exists for logs only and is never sent to the client.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final @Nullable String reason;

	private final Map<String, Object> details;

	public ApiException(ErrorCode code) {
		this(code, null);
	}

	public ApiException(ErrorCode code, @Nullable Enum<?> reason) {
		this(code, reason, Map.of());
	}

	/**
	 * @param details extra Problem Details members the SPA reads beside the reason, such as
	 * {@code attemptsLeft}: numbers and short words only, never personal data
	 */
	public ApiException(ErrorCode code, @Nullable Enum<?> reason, Map<String, Object> details) {
		super(reason == null ? code.name() : code.name() + " (" + reason.name() + ")", null, false, false);
		this.code = code;
		this.reason = reason == null ? null : reason.name();
		this.details = Map.copyOf(details);
	}

	public ErrorCode code() {
		return this.code;
	}

	public Optional<String> reason() {
		return Optional.ofNullable(this.reason);
	}

	public Map<String, Object> details() {
		return this.details;
	}

}
