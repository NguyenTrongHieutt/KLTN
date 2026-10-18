package vn.vibefix.notification.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.notification.internal.NotificationInbox.TodoItem;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.Gate;

/**
 * The caller's open to-dos (FR-28, AD-27): the next-action strip shows the first, CUS-1, MEN-1 and
 * STU-1 list them, and the tab bar counts them. There is no way to close or delete one here.
 */
@RestController
@RequestMapping("/api/v1/todos")
@Access(Gate.SIGNED_IN)
class TodoController {

	/**
	 * @param items the open to-dos in order, at most {@value NotificationInbox#TODO_LIMIT}
	 * @param total every open to-do
	 */
	record TodoList(List<TodoItem> items, long total) {
	}

	private final NotificationInbox inbox;

	private final CurrentCaller caller;

	TodoController(NotificationInbox inbox, CurrentCaller caller) {
		this.inbox = inbox;
		this.caller = caller;
	}

	/** @param jobId only that Job's to-dos, for a Job's screens */
	@GetMapping
	TodoList todos(@RequestParam(required = false) @Nullable UUID jobId) {
		UUID account = this.caller.get().requireAccountId();
		return new TodoList(this.inbox.todos(account, jobId), this.inbox.todoCount(account, jobId));
	}

}
