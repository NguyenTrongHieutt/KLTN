package vn.vibefix.notification.internal;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.notification.internal.NotificationInbox.NotificationItem;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.PageResponse;

/**
 * ALL-1 and the bell (FR-48, UX-DR16, UX-DR11): the caller's own notifications of the last 90
 * days, newest first, and their unread count. Another account's notification answers
 * {@code NOT_FOUND} (AD-20).
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Access(Gate.SIGNED_IN)
class NotificationController {

	/** @param count unread notifications of the last 90 days */
	record UnreadCount(long count) {
	}

	private final NotificationInbox inbox;

	private final CurrentCaller caller;

	NotificationController(NotificationInbox inbox, CurrentCaller caller) {
		this.inbox = inbox;
		this.caller = caller;
	}

	@GetMapping
	PageResponse<NotificationItem> notifications(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
		UUID account = this.caller.get().requireAccountId();
		return new PageResponse<>(this.inbox.notifications(account, pageable.getPageNumber(), pageable.getPageSize()),
				pageable.getPageNumber(), pageable.getPageSize(), this.inbox.count(account));
	}

	@GetMapping("/unread-count")
	UnreadCount unreadCount() {
		return new UnreadCount(this.inbox.unread(this.caller.get().requireAccountId()));
	}

	@PostMapping("/{notificationId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void read(@PathVariable UUID notificationId) {
		if (!this.inbox.markRead(this.caller.get().requireAccountId(), notificationId)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void readAll() {
		this.inbox.markAllRead(this.caller.get().requireAccountId());
	}

}
