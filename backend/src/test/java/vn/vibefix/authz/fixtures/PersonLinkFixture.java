package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.MutableClock;

/**
 * ADM-2's account (FR-59, AD-19, Story 19.7): another person's account ({@code {accountId}}) that an
 * Administrator opens, locks, unlocks, and erases on request. It shares an {@code EMAIL} person-link
 * signal with one more account, so "Tài khoản có thể cùng một người" lists a pair; the signal's HMAC is
 * the canary {@code PERSON_SIGNAL}, which no answer may ever hold, ADM-2's included.
 */
public class PersonLinkFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final Canaries canaries = Canaries.load();

	public PersonLinkFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void seed(MatrixWorld world) {
		UUID account = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		for (UUID holder : List.of(account, other)) {
			this.jdbc.sql("""
					INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
					VALUES (:id, :account, 'EMAIL', 'GOOGLE_EMAIL', :hmac, 1, :now)
					""")
				.param("id", UUID.randomUUID())
				.param("account", holder)
				.param("hmac", this.canaries.get("PERSON_SIGNAL").value())
				.param("now", Timestamp.from(MutableClock.DEFAULT_INSTANT))
				.update();
		}
		world.put("accountId", account);
	}

}
