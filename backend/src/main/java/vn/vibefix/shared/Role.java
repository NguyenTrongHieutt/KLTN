package vn.vibefix.shared;

/**
 * The permanent role of an account (AD-19). Customers, Mentors, and Students sign in with GitHub;
 * Administrators sign in on their own path with a password and TOTP.
 */
public enum Role {

	CUSTOMER, MENTOR, STUDENT, ADMINISTRATOR

}
