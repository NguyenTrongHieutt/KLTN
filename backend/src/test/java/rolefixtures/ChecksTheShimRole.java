package rolefixtures;

import vn.vibefix.identity.internal.Role;

/**
 * Deliberately decides on the test shim's account role, as production code may never do, so that
 * {@code RoleRuleTest} can prove its rule fires on a dependency on {@code vn.vibefix.identity.internal.Role}.
 */
public class ChecksTheShimRole {

	boolean mentor(Role role) {
		return role == Role.MENTOR;
	}

}
