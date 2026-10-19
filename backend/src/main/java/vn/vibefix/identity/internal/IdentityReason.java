package vn.vibefix.identity.internal;

/**
 * The {@code reason} of an {@code identity} refusal (AD-23); the SPA reads
 * {@code reason.identity.{REASON}} in {@code vi.json} (EXPERIENCE.md, Messages from Error Codes).
 */
enum IdentityReason {

	/** The school email's domain is not on the allowlist (FR-5, FR-61). */
	EMAIL_DOMAIN_NOT_ALLOWED,

	/** The school email already verifies another account (FR-5). */
	ALREADY_USED_FOR_VERIFICATION,

	/** The code does not match; the verification view says how many attempts are left. */
	WRONG_CODE,

	/** The code is older than 10 minutes. */
	CODE_EXPIRED,

	/** The code has had its 5 attempts. */
	TOO_MANY_ATTEMPTS,

	/** An Administrator sign-in whose email, password, or TOTP code is wrong; never which one. */
	ADMIN_SIGN_IN_FAILED,

	/** 5 failed Administrator sign-ins within 15 minutes lock that email for 15 minutes (AD-34). */
	ADMIN_SIGN_IN_LOCKED,

	/** A wrong step-up code (SG-10); the answer says how many attempts are left. */
	STEP_UP_FAILED,

	/** The email is an Administrator's, or an open enrolment's (FR-2). */
	ADMINISTRATOR_EMAIL_IN_USE,

	/** The enrolment's code does not match the secret it showed. */
	ENROLMENT_CODE_WRONG,

	/** A new-work command from a Mentor who is not {@code APPROVED} (FR-74, AD-19). */
	MENTOR_NOT_APPROVED,

	/** A command from a locked or erased account (FR-59, AD-19). */
	ACCOUNT_LOCKED,

	/** The LinkedIn profile is on another live Mentor application (AD-19). */
	MENTOR_LINKEDIN_IN_USE,

	/** The first submission of a Mentor application without the current personal-data notice accepted. */
	PRIVACY_NOTICE_REQUIRED

}
