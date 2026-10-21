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
 * A Customer whose kept GitHub token is gone or refused (FR-13, author decision 2026-10-21): the
 * contributor check keeps failing closed, and the Customer is asked to sign in again with a to-do and a
 * notice, at most one notice a day. Both are written in their own transaction, so the refused command
 * that found the token unusable does not undo them. Signing in again stores a new token and closes the
 * to-do ({@link SignIns}).
 */
@Component
class TokenRenewals {

	static final String TODO = "identity.sign_in_again";

	static final String NOTICE = "identity.sign_in_again";

	/** The page that starts GitHub's sign-in again for a signed-in Customer. */
	static final String LINK = "/dang-nhap-lai";

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
	void ask(UUID customerId) {
		this.todos.open(new TodoRequest(TODO, customerId, Audience.SUBJECT, customerId, Map.of(), LINK, null, null));
		LocalDate today = LocalDate.ofInstant(this.clock.instant(), VIETNAM);
		this.notifications.send(new NotificationRequest(NOTICE,
				UUID.nameUUIDFromBytes((customerId + ":sign-in-again:" + today).getBytes(StandardCharsets.UTF_8)),
				List.of(new Recipient(customerId, Audience.SUBJECT)), Map.of(), LINK, null, customerId));
	}

	/** A new token is kept: nothing is left to ask. */
	void renewed(UUID customerId) {
		this.todos.close(TODO, customerId, customerId);
	}

}
