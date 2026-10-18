package vn.vibefix.notification.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.ErasureBlocker;
import vn.vibefix.shared.PersonalDataParticipant;
import vn.vibefix.shared.PersonalDataSection;

/**
 * Notifications' part in AD-24: an export holds the account's own notifications and to-dos; an
 * erasure deletes them. They name only the account they are addressed to.
 */
@Component
class NotificationPersonalData implements PersonalDataParticipant {

	private final JdbcClient jdbc;

	NotificationPersonalData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> tables() {
		return Set.of("notification.notification", "notification.todo");
	}

	@Override
	public Optional<PersonalDataSection> export(UUID accountId) {
		List<Map<String, Object>> notifications = this.jdbc.sql("""
				SELECT type, params::text AS params, link, created_at, read_at FROM notification.notification
				WHERE account_id = :account ORDER BY created_at
				""").param("account", accountId).query().listOfRows();
		List<Map<String, Object>> todos = this.jdbc.sql("""
				SELECT todo_key, params::text AS params, link, deadline_at, opened_at, closed_at FROM notification.todo
				WHERE account_id = :account ORDER BY opened_at
				""").param("account", accountId).query().listOfRows();
		if (notifications.isEmpty() && todos.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("notifications", notifications);
		content.put("todos", todos);
		return Optional.of(new PersonalDataSection("notification", content));
	}

	@Override
	public void eraseAccount(UUID accountId) {
		this.jdbc.sql("DELETE FROM notification.notification WHERE account_id = :account")
			.param("account", accountId)
			.update();
		this.jdbc.sql("DELETE FROM notification.todo WHERE account_id = :account").param("account", accountId).update();
	}

	@Override
	public void deleteProduct(UUID productId) {
		// Notifications name Products only through their parameters, which go with the recipient's account.
	}

	@Override
	public List<ErasureBlocker> erasureBlockers(UUID accountId) {
		return List.of();
	}

}
