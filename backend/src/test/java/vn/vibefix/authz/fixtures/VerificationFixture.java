package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/**
 * Student verification (FR-5): another Student is verified with the {@code SCHOOL_EMAIL} canary,
 * which no caller but an Administrator may ever receive. A Student caller's code request is sent
 * because the dev seed, which integration tests load, allowlists {@code truong.test}. A suite may
 * seed several rows on one database, and the address verifies one account, so a second seed keeps
 * the first.
 */
public class VerificationFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final Canaries canaries = Canaries.load();

	public VerificationFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void seed(MatrixWorld world) {
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		UUID verified = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		this.jdbc.sql("""
				INSERT INTO identity.student_verification (account_id, method, school_email, verified_at, valid_until)
				VALUES (:account, 'SCHOOL_EMAIL', :canary, :now, DATE '2026-09-30')
				ON CONFLICT (school_email) DO NOTHING
				""")
			.param("account", verified)
			.param("canary", this.canaries.get("SCHOOL_EMAIL").value())
			.param("now", now)
			.update();
	}

}
