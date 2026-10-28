package vn.vibefix.identity.internal;

/**
 * Test shim (Story 20.3): the account roles of the rows from before Google sign-in, which tests still create
 * through {@link IdentityTestSupport#insertAccount} and sign in with {@link IdentityTestSupport#signedIn}. No
 * production class has a role any more ({@code RoleRuleTest} fails the build on one): a {@code USER}'s rights come
 * from their role in each Job and from their capabilities. A shim role only seeds an account's kind: every role but
 * {@code ADMINISTRATOR} is a {@code USER} with GitHub linked. It writes no role: Story 20.6 dropped
 * {@code identity.account.role}.
 */
public enum Role {

	CUSTOMER, MENTOR, STUDENT, ADMINISTRATOR

}
