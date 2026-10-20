package vn.vibefix.scanning.internal;

/** {@code scanning}'s refusal reasons ({@code reason.scanning.*} in {@code vi.json}). */
enum ScanningReason {

	/** FR-64: only a false positive can be reopened. */
	NOT_A_FALSE_POSITIVE,

	/** The Finding is resolved already. */
	FINDING_CLOSED,

	/** Rotation is for credentials; personal data is resolved by a clean repository (FR-65). */
	NOT_A_CREDENTIAL

}
