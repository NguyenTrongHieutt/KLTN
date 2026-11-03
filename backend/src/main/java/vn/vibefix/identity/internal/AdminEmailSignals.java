package vn.vibefix.identity.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records the {@code ADMIN_EMAIL} signal of every Administrator who lacks it, once at startup (AD-19, A-17): those created
 * before Story 22.3 have none, and a migration cannot compute one, since the HMAC key is a secret file. A later start finds
 * every row already there and adds nothing, and a rotated key's rows arrive the same way. It does not run under the
 * {@code cli} profile, whose commands create Administrators themselves.
 */
@Component
@Profile("!cli")
class AdminEmailSignals implements ApplicationRunner {

	private record Administrator(UUID accountId, String email) {
	}

	private final JdbcClient jdbc;

	private final JdbcPersonSignals signals;

	private final TransactionTemplate transactions;

	AdminEmailSignals(JdbcClient jdbc, JdbcPersonSignals signals, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.signals = signals;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Override
	public void run(ApplicationArguments args) {
		backfill();
	}

	/** Each Administrator's email, enrolled or still enrolling; an erased account is left alone. */
	void backfill() {
		this.transactions.executeWithoutResult(status -> administrators()
			.forEach(admin -> this.signals.record(admin.accountId(), JdbcPersonSignals.Source.ADMIN_EMAIL, admin.email())));
	}

	private List<Administrator> administrators() {
		return this.jdbc.sql("""
				SELECT a.id, coalesce(c.email, e.email) AS email FROM identity.account a
				LEFT JOIN identity.administrator_credential c ON c.account_id = a.id
				LEFT JOIN identity.administrator_enrolment e ON e.account_id = a.id
				WHERE a.kind = 'ADMINISTRATOR' AND a.erased_at IS NULL AND coalesce(c.email, e.email) IS NOT NULL
				ORDER BY a.id
				""").query((rs, n) -> new Administrator(rs.getObject("id", UUID.class), rs.getString("email"))).list();
	}

}
