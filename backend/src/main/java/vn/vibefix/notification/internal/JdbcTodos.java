package vn.vibefix.notification.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.TodoType;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.IdGenerator;

/** The to-do store (AD-27): open and close in the owning module's transaction. */
@Component
class JdbcTodos implements Todos {

	private final JdbcClient jdbc;

	private final NotificationRegistry registry;

	private final NotificationJson json;

	private final IdGenerator ids;

	private final Clock clock;

	JdbcTodos(JdbcClient jdbc, NotificationRegistry registry, NotificationJson json, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.registry = registry;
		this.json = json;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void open(TodoRequest request) {
		TodoType type = this.registry.requireTodoType(request.key());
		NotificationRegistry.checkParams(type.key(), type.params(), request.params());
		NotificationRegistry.checkLink(type.key(), request.link());
		this.registry.checkRecipient(type.key(), type.audiences(), request.recipientAccountId(), request.audience(),
				request.jobId(), request.subjectId(), null);
		this.jdbc.sql("""
				INSERT INTO notification.todo
				    (id, todo_key, account_id, subject_id, tier, params, link, deadline_at, job_id, opened_at)
				VALUES (:id, :key, :account, :subject, :tier, CAST(:params AS jsonb), :link, :deadline, :jobId, :now)
				ON CONFLICT (todo_key, account_id, subject_id) WHERE closed_at IS NULL DO NOTHING
				""")
			.param("id", this.ids.newId())
			.param("key", type.key())
			.param("account", request.recipientAccountId())
			.param("subject", request.subjectId())
			.param("tier", request.tier() != null ? request.tier() : type.tier())
			.param("params", this.json.write(request.params()))
			.param("link", request.link())
			.param("deadline", request.deadlineAt() == null ? null : Timestamp.from(request.deadlineAt()))
			.param("jobId", request.jobId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void close(String key, UUID recipientAccountId, UUID subjectId) {
		this.jdbc.sql("""
				UPDATE notification.todo SET closed_at = :now
				WHERE todo_key = :key AND account_id = :account AND subject_id = :subject AND closed_at IS NULL
				""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("key", key)
			.param("account", recipientAccountId)
			.param("subject", subjectId)
			.update();
	}

	@Override
	public boolean isOpen(String key, UUID recipientAccountId, UUID subjectId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM notification.todo
				WHERE todo_key = :key AND account_id = :account AND subject_id = :subject AND closed_at IS NULL)
				""")
			.param("key", key)
			.param("account", recipientAccountId)
			.param("subject", subjectId)
			.query(Boolean.class)
			.single();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void closeAll(String key, UUID subjectId) {
		this.jdbc.sql("""
				UPDATE notification.todo SET closed_at = :now
				WHERE todo_key = :key AND subject_id = :subject AND closed_at IS NULL
				""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("key", key)
			.param("subject", subjectId)
			.update();
	}

}
