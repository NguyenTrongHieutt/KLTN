package vn.vibefix.authz.fixtures;

import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;

/**
 * Administrator enrolment (FR-2): a token no enrolment has, so the enrolment link's rows check who may
 * open one; an unknown token finds nothing.
 */
public class AdministratorFixture implements MatrixFixture {

	@Override
	public void seed(MatrixWorld world) {
		world.put("token", "ab".repeat(16));
		// ADM-12 edits seeded catalog entries (FR-72): the first Category and the React Skill.
		world.put("categoryId", "0192b000-0000-7000-8000-000000000001");
		world.put("skillId", "0192b000-0000-7000-8001-000000000004");
	}

}
