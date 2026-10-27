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
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.MutableClock;

/**
 * Realizes {@link MatrixCaller#SUBJECT} (AD-20): one Customer account that is the subject of every
 * record the matrix reads as its own. It has a notification ({@code {notificationId}}) and a to-do
 * with the canaries {@code NOTIFICATION_PARAM} and {@code TODO_PARAM} as their parameters, and a
 * profile ({@code {customerId}}, Story 19.3) whose contact email is the canary {@code CONTACT_EMAIL}, and a Skill
 * suggestion ({@code {suggestionId}}) named with the canary {@code SKILL_SUGGESTION} (Story 16.4). A later
 * subject kind seeds its records for this same account, because one fixture realizes each caller.
 */
public class SubjectFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final Canaries canaries = Canaries.load();

	private UUID subject;

	public SubjectFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void seed(MatrixWorld world) {
		this.subject = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
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
		this.jdbc.sql("""
				INSERT INTO identity.profile (account_id, kind, display_name, contact_email, updated_at)
				VALUES (:account, 'INDIVIDUAL', 'Chủ sản phẩm', :canary, :now)
				""")
			.param("account", this.subject)
			.param("canary", this.canaries.get("CONTACT_EMAIL").value())
			.param("now", now)
			.update();
		UUID suggestion = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO admin.skill_suggestion (id, suggester_account_id, suggester_role, name, status, created_at)
				VALUES (:id, :account, 'CUSTOMER', :canary, 'OPEN', :now)
				""")
			.param("id", suggestion)
			.param("account", this.subject)
			.param("canary", this.canaries.get("SKILL_SUGGESTION").value())
			.param("now", now)
			.update();
		world.put("notificationId", notification);
		world.put("customerId", this.subject);
		world.put("suggestionId", suggestion);
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		if (caller != MatrixCaller.SUBJECT) {
			return Optional.empty();
		}
		return Optional.of(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
			.authentication(IdentityTestSupport.authentication(this.subject, Role.CUSTOMER)));
	}

}
