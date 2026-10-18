package vn.vibefix.shared;

/**
 * Who may reach an endpoint at all, declared with {@link Access}. The gate only keeps callers out;
 * whether a caller may see or change an object is decided by the owning module's policy (AD-20).
 */
public enum Gate {

	/** Anyone, signed in or not: public pages, and webhooks that verify their own signature. */
	PUBLIC,

	/** Any signed-in account; an anonymous caller gets {@code 401}. */
	SIGNED_IN,

	/**
	 * Administrators only. An anonymous caller gets {@code 401}; any other account gets
	 * {@code 404 NOT_FOUND}, so the Administrator area stays invisible.
	 */
	ADMINISTRATOR

}
