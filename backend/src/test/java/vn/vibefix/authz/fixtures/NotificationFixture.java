package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/**
 * Seeds a notification and a to-do of one account, {@code {notificationId}}, with the canaries
 * {@code NOTIFICATION_PARAM} and {@code TODO_PARAM} as their parameters, and realizes that account
 * as {@link MatrixCaller#SUBJECT} (AD-20): only it may read them.
 */
public class NotificationFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final Canaries canaries = Canaries.load();

	private UUID subject;

	public NotificationFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void seed(MatrixWorld world) {
		this.subject = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		UUID notification = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO notification.notification (id, account_id, type, causing_record_id, params, link, created_at)
				VALUES (:id, :account, 'test.subject_update', :cause, jsonb_build_object('name', CAST(:canary AS text)),
				        '/viec-can-lam', :now)
				""")
			.param("id", notification)
			.param("account", this.subject)
			.param("cause", UUID.randomUUID())
			.param("canary", this.canaries.get("NOTIFICATION_PARAM").value())
			.param("now", now)
			.update();
		this.jdbc.sql("""
				INSERT INTO notification.todo (id, todo_key, account_id, subject_id, tier, params, link, opened_at)
				VALUES (:id, 'test.blocks', :account, :account, 2, jsonb_build_object('name', CAST(:canary AS text)),
				        '/viec-can-lam', :now)
				""")
			.param("id", UUID.randomUUID())
			.param("account", this.subject)
			.param("canary", this.canaries.get("TODO_PARAM").value())
			.param("now", now)
			.update();
		world.put("notificationId", notification);
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		if (caller != MatrixCaller.SUBJECT) {
			return Optional.empty();
		}
		return Optional.of(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
			.authentication(IdentityTestSupport.authentication(this.subject, Role.STUDENT)));
	}

}
