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
	}

}
