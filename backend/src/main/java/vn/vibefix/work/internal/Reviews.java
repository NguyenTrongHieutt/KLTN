package vn.vibefix.work.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.Boards.Status;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkTextCheck.MatchView;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;

/**
 * The Review Gate (FR-25, AD-39): the assignee submits a Work Item in "Đang làm" with the four
 * Self-check answers and an optional note; only the Mentor approves it to "Xong" or returns it to
 * "Đang làm", each with a written comment. Each step is one history entry written with the status
 * change, tells the other side, and opens or closes their to-dos.
 */
@Component
class Reviews {

	static final String REVIEW_REQUESTED = "work.review_requested";

	static final String APPROVED = "work.work_item_approved";

	static final String RETURNED = "work.work_item_returned";

	static final String REVIEW_TODO = "work.review_work_item";

	static final String FIX_TODO = "work.fix_returned_item";

	enum Decision {
		APPROVE, RETURN
	}

	/** The Self-check Checklist: every answer is required (AD-39). */
	record SelfCheck(boolean meetsCriteria, boolean tested, boolean docsUpdated, boolean noSecrets) {

		boolean complete() {
			return this.meetsCriteria && this.tested && this.docsUpdated && this.noSecrets;
		}

		Map<String, Boolean> answers() {
			Map<String, Boolean> answers = new LinkedHashMap<>();
			answers.put("meetsCriteria", this.meetsCriteria);
			answers.put("tested", this.tested);
			answers.put("docsUpdated", this.docsUpdated);
			answers.put("noSecrets", this.noSecrets);
			return answers;
		}

	}

	private final Boards boards;

	private final JdbcClient jdbc;

	private final WorkTextCheck check;

	private final StudentDirectory students;

	private final Notifications notifications;

	private final Todos todos;

	private final Clock clock;

	Reviews(Boards boards, JdbcClient jdbc, WorkTextCheck check, StudentDirectory students, Notifications notifications,
			Todos todos, Clock clock) {
		this.boards = boards;
		this.jdbc = jdbc;
		this.check = check;
		this.students = students;
		this.notifications = notifications;
		this.todos = todos;
		this.clock = clock;
	}

	/** "Gửi duyệt": the assignee's submission from "Đang làm" (FR-25). */
	@Transactional
	Item submit(BoardViewer writer, UUID itemId, long version, SelfCheck selfCheck, String note, Resolution resolution) {
		Item item = locked(writer, itemId, version);
		if (!writer.accountId().equals(item.assigneeId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.NOT_ASSIGNEE);
		}
		if (item.status() != Status.IN_PROGRESS) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.MOVE_NOT_ALLOWED);
		}
		if (!selfCheck.complete()) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.SELF_CHECK_INCOMPLETE);
		}
		String text = checked(note, resolution);
		UUID entry = moveTo(item, Status.IN_REVIEW, writer, "SUBMITTED", text, selfCheck.answers());
		this.todos.close(FIX_TODO, writer.accountId(), item.id());
		this.notifications.send(new NotificationRequest(REVIEW_REQUESTED, entry,
				List.of(new Recipient(writer.mentorId(), Audience.MENTOR)),
				Map.of("name", name(writer.accountId()), "key", item.key().toString()), link(item), item.jobId(), null));
		this.todos.open(new TodoRequest(REVIEW_TODO, writer.mentorId(), Audience.MENTOR, item.id(),
				Map.of("key", item.key().toString(), "title", item.title()), link(item), null, item.jobId()));
		return this.boards.item(itemId).orElseThrow();
	}

	/** "Duyệt" or "Trả lại": only the Mentor, only from "Chờ duyệt", always with a comment (FR-25). */
	@Transactional
	Item review(BoardViewer writer, UUID itemId, long version, Decision decision, String comment, Resolution resolution) {
		Item item = locked(writer, itemId, version);
		if (!writer.mentor()) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.MENTOR_ONLY);
		}
		if (item.status() != Status.IN_REVIEW) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.MOVE_NOT_ALLOWED);
		}
		if (comment.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.REVIEW_COMMENT_REQUIRED);
		}
		String text = checked(comment.strip(), resolution);
		boolean approve = decision == Decision.APPROVE;
		UUID entry = moveTo(item, approve ? Status.DONE : Status.IN_PROGRESS, writer, approve ? "APPROVED" : "RETURNED",
				text, null);
		this.todos.closeAll(REVIEW_TODO, item.id());
		UUID assignee = item.assigneeId();
		if (assignee != null) {
			Map<String, String> params = approve ? Map.of("key", item.key().toString())
					: Map.of("key", item.key().toString(), "reason", text);
			this.notifications.send(new NotificationRequest(approve ? APPROVED : RETURNED, entry,
					List.of(new Recipient(assignee, Audience.CREW_ACTIVE)), params, link(item), item.jobId(), null));
			if (!approve) {
				this.todos.open(new TodoRequest(FIX_TODO, assignee, Audience.CREW_ACTIVE, item.id(),
						Map.of("key", item.key().toString()), link(item), null, item.jobId()));
			}
		}
		return this.boards.item(itemId).orElseThrow();
	}

	private Item locked(BoardViewer writer, UUID itemId, long version) {
		Item item = this.boards.lockItem(itemId)
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (item.version() != version) {
			throw new ApiException(ErrorCode.STALE);
		}
		return item;
	}

	private String checked(String text, Resolution resolution) {
		List<MatchView> matches = new ArrayList<>();
		String masked = this.check.masked(text, matches);
		WorkTextCheck.requireResolved(matches, resolution);
		return masked;
	}

	private UUID moveTo(Item item, Status to, BoardViewer writer, String action, String comment,
			Map<String, Boolean> selfCheck) {
		this.jdbc.sql("UPDATE work.work_item SET status = :to, version = version + 1, updated_at = :now WHERE id = :id")
			.param("id", item.id())
			.param("to", to.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("from", item.status().name());
		change.put("to", to.name());
		return this.boards.history(item.id(), item.jobId(), writer.accountId(), action, Map.of("status", change), null,
				comment.isEmpty() ? null : comment, selfCheck);
	}

	private String name(UUID student) {
		return this.students.cards(List.of(student))
			.stream()
			.findFirst()
			.map(card -> Objects.requireNonNullElse(card.name(), "@" + card.githubLogin()))
			.orElse("");
	}

	private static String link(Item item) {
		return "/cong-viec/" + item.jobId() + "/bang";
	}

}
