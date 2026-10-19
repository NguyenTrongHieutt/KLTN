package vn.vibefix.scanning;

/** What a Finding is about: they are resolved differently (FR-8, FR-65). */
public enum FindingCategory {

	/** A credential: rotate it, or mark it a false positive. */
	CREDENTIAL,

	/** End users' personal data: register a clean repository, or mark it a false positive. */
	PERSONAL_DATA

}
