package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.Notices;
import vn.vibefix.shared.IdGenerator;

/** {@link Notices} on {@code identity.acknowledgment}, beside the privacy notice's acknowledgments. */
@Component
class JdbcNotices implements Notices {

	private final JdbcClient jdbc;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcNotices(JdbcClient jdbc, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public boolean acknowledged(UUID accountId, String notice, String version) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM identity.acknowledgment
				               WHERE account_id = :account AND notice = :notice AND notice_version = :version)
				""").param("account", accountId).param("notice", notice).param("version", version)
			.query(Boolean.class)
			.single();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void acknowledge(UUID accountId, String notice, String version) {
		this.jdbc.sql("""
				INSERT INTO identity.acknowledgment (id, account_id, notice, notice_version, acknowledged_at)
				VALUES (:id, :account, :notice, :version, :now)
				ON CONFLICT (account_id, notice, notice_version) DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("account", accountId)
			.param("notice", notice)
			.param("version", version)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

}
