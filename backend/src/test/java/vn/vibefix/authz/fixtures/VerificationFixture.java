package vn.vibefix.authz.fixtures;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;

/**
 * Student verification (FR-5, AD-42): another account is verified, its live row keyed by the
 * {@code SCHOOL_EMAIL} canary as its subject HMAC, which no caller may ever receive: the address is stored
 * nowhere, and no answer shows an HMAC. A caller's code request is sent because the dev seed, which
 * integration tests load, allowlists {@code truong.test}. A suite may seed several rows on one database,
 * and a subject verifies one live account, so a second seed keeps the first.
 */
public class VerificationFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final Canaries canaries = Canaries.load();

	public VerificationFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void seed(MatrixWorld world) {
		UUID verified = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		IdentityTestSupport.verifyStudent(this.jdbc, verified, LocalDate.parse("2026-09-30"),
				this.canaries.get("SCHOOL_EMAIL").value());
	}

}
