package vn.vibefix.shared;

/**
 * The two ways a module's policy refuses (AD-20, SG-31). A policy decides on role, relationship,
 * and object state before any read or write, and allows only what a rule grants.
 */
public final class Policies {

	private Policies() {
	}

	/**
	 * Refuses with {@code NOT_FOUND} unless the caller may see the object. The answer is the same
	 * as for an object that does not exist, so a refusal reveals nothing.
	 */
	public static void requireVisible(boolean visible) {
		if (!visible) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	/**
	 * Refuses with {@code FORBIDDEN} and the module's {@code reason} unless the caller, who can see
	 * the object, may act on it (for example {@code MENTOR_NOT_APPROVED}).
	 */
	public static void requireAllowed(boolean allowed, Enum<?> reason) {
		if (!allowed) {
			throw new ApiException(ErrorCode.FORBIDDEN, reason);
		}
	}

}
