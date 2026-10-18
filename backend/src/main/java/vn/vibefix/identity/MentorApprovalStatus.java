package vn.vibefix.identity;

/**
 * Where a Mentor account stands with the Administrators (AD-19, FR-74). A new Mentor account starts
 * {@code PENDING} and can do nothing but complete its application until it is {@code APPROVED}.
 */
public enum MentorApprovalStatus {

	PENDING, APPROVED, REJECTED, REVOKED

}
