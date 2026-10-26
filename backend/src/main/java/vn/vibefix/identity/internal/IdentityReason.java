package vn.vibefix.identity.internal;

/**
 * The {@code reason} of an {@code identity} refusal (AD-23); the SPA reads
 * {@code reason.identity.{REASON}} in {@code vi.json} (EXPERIENCE.md, Messages from Error Codes).
 */
enum IdentityReason {

	/** The school email's domain is not on the allowlist (FR-5, FR-61). */
	EMAIL_DOMAIN_NOT_ALLOWED,

	/** The student card's school and number already verify another account (FR-5, until Story 19.7). */
	ALREADY_USED_FOR_VERIFICATION,

	/**
	 * The school email's keyed HMAC is on another account's live verification (FR-5, AD-23, AD-42). The SPA's
	 * copy key is {@code ALREADY_USED_FOR_VERIFICATION}.
	 */
	STUDENT_EMAIL_IN_USE,

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
	PRIVACY_NOTICE_REQUIRED,

	/** A rejected Mentor saves before the 30-day wait ends; the answer's {@code date} says when it does. */
	MENTOR_REAPPLY_TOO_SOON,

	/** Administrators are managed on ADM-11, never locked from ADM-2 (FR-59). */
	ADMINISTRATOR_ACCOUNT,

	/** The account is locked already. */
	ALREADY_LOCKED,

	/** The account is not locked. */
	NOT_LOCKED,

	/** The Student has no current verification to revoke. */
	NOT_VERIFIED,

	/** The Mentor is not approved, so there is nothing to revoke. */
	NOT_APPROVED,

	/** NFR-11: the account's deletion was requested already. */
	ALREADY_REQUESTED,

	/** NFR-11: the person has not requested their account's deletion. */
	NO_DELETION_REQUEST,

	/** NFR-11, AD-24: a running Job, an open Dispute, or a Team Issue keeps the account from being erased now. */
	ERASURE_BLOCKED,

	/** FR-80, AD-19: a command that creates work, from an account with no linked GitHub account. */
	GITHUB_NOT_LINKED,

	/**
	 * FR-80, R14: unlinking while a live Product, an open bid, or a role in a Job that is not Closed or Cancelled
	 * keeps the link; the answer's {@code blockers} name them. A re-link must return the same numeric GitHub ID
	 * whatever blocks it; GitHub's return refuses another one on ACC-2 ({@code loi=sai-tai-khoan}), as it refuses
	 * an ID another account holds ({@code loi=da-lien-ket-tai-khoan-khac}). The SPA's copy key is
	 * {@code GITHUB_CHANGE_BLOCKED} (SG-58).
	 */
	GITHUB_LINK_IN_USE,

	/** This environment has no linking OAuth App yet (AD-25): nothing can be linked. */
	GITHUB_NOT_SET_UP

}
