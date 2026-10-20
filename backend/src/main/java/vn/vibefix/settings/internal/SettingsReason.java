package vn.vibefix.settings.internal;

/**
 * The {@code reason} of a {@code settings} refusal (AD-23); the SPA reads {@code reason.settings.{REASON}}
 * in {@code vi.json}.
 */
enum SettingsReason {

	/** A value outside its rule's bounds (FR-61). */
	RULE_OUT_OF_BOUNDS,

	/** A school domain that is not a domain name. */
	DOMAIN_INVALID

}
