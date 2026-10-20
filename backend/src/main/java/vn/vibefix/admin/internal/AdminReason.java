package vn.vibefix.admin.internal;

/**
 * The {@code reason} of an {@code admin} refusal (AD-23); the SPA reads {@code reason.admin.{REASON}} in
 * {@code vi.json}.
 */
enum AdminReason {

	/** FR-78: fewer than 3 private feedbacks from 2 different Students on 2 different Jobs. */
	FEEDBACK_NOT_ENOUGH,

	/** FR-60: the content is hidden already. */
	ALREADY_HIDDEN,

	/** FR-64: the operation is not failed, so there is nothing to retry. */
	NOT_FAILED

}
