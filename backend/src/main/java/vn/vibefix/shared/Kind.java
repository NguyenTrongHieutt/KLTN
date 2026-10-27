package vn.vibefix.shared;

/**
 * Whose account it is (AD-19, Story 19.1): a person's, who holds a role in each Job and none on the account,
 * or an Administrator's, who holds no role in any Job (FR-2). It never changes, and {@link Caller} carries it.
 */
public enum Kind {

	USER, ADMINISTRATOR

}
