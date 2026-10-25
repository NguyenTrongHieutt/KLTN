package vn.vibefix.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;

/**
 * An account whose kept GitHub token is gone or refused at an AD-13 re-check (FR-13, FR-18, FR-37): the
 * check keeps failing closed, and the account is asked to re-link GitHub with a to-do and a notice, at
 * most one notice a day; ACC-2 then shows "Cần liên kết lại". Both are written in their own transaction,
 * so the refused command that found the token unusable does not undo them. A re-link, which must return
 * the same numeric GitHub ID, keeps a new token and closes the to-do ({@link GitHubLinksImpl}). This
 * replaced "sign in again" in Story 19.2.
 */
@Component
class TokenRenewals {

	static final String TODO = "identity.relink_github";

	static final String NOTICE = "identity.relink_github";

	/** ACC-2, whose GitHub row re-links (UX-DR91). */
	static final String LINK = "/ho-so/xac-minh";

	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	private final Todos todos;

	private final Notifications notifications;

	private final Clock clock;

	TokenRenewals(Todos todos, Notifications notifications, Clock clock) {
		this.todos = todos;
		this.notifications = notifications;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void ask(UUID accountId) {
		this.todos.open(new TodoRequest(TODO, accountId, Audience.SUBJECT, accountId, Map.of(), LINK, null, null));
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		this.notifications.send(new NotificationRequest(NOTICE,
				UUID.nameUUIDFromBytes((accountId + ":relink-github:" + today).getBytes(StandardCharsets.UTF_8)),
				List.of(new Recipient(accountId, Audience.SUBJECT)), Map.of(), LINK, null, accountId));
	}

	/** GitHub was linked again: nothing is left to ask. */
	void renewed(UUID accountId) {
		this.todos.close(TODO, accountId, accountId);
	}

	/** Whether the account is asked to re-link: ACC-2's "Cần liên kết lại". */
	boolean asked(UUID accountId) {
		return this.todos.isOpen(TODO, accountId, accountId);
	}

}
