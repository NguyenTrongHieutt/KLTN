package vn.vibefix.work.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.AgreementCriteria;
import vn.vibefix.job.AgreementCriteria.MilestoneInForce;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.HeldMilestones;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.WorkItemKey;
import vn.vibefix.work.internal.Boards.ChecklistLine;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.Boards.Priority;
import vn.vibefix.work.internal.Boards.Status;
import vn.vibefix.work.internal.Boards.Type;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkTextCheck.MatchView;
import vn.vibefix.work.internal.WorkTextCheck.Resolution;

/**
 * Creating and editing Work Items (FR-23, FR-47, AD-23, AD-33, AD-39): every save carries the fields
 * in full, passes the FR-47 check, and writes one history entry with each changed field. Only the
 * Mentor sets the assignee or the priority, and the assignee must be an active Crew member with an
 * unlocked account, who then gets {@code work.work_item_assigned}. A seeded Story keeps its type and
 * Milestone, and an edit carries the version it was made on.
 */
@Component
class WorkItems {

	static final String ASSIGNED = "work.work_item_assigned";

	/** The fields of one save. */
	record Draft(Type type, String title, String description, List<ChecklistLine> checklist, UUID milestoneId,
			@Nullable Priority priority, @Nullable BigDecimal estimateHours, @Nullable UUID assigneeId) {

		Draft {
			checklist = List.copyOf(checklist);
		}

	}

	private final Boards boards;

	private final JdbcClient jdbc;

	private final AgreementCriteria criteria;

	private final Crews crews;

	private final StudentDirectory students;

	private final MentorDirectory mentors;

	private final WorkTextCheck check;

	private final Notifications notifications;

	private final HeldMilestones held;

	private final IdGenerator ids;

	private final Clock clock;

	private final Reassignments reassignments;

	WorkItems(Boards boards, JdbcClient jdbc, AgreementCriteria criteria, Crews crews, StudentDirectory students,
			MentorDirectory mentors, WorkTextCheck check, Notifications notifications, HeldMilestones held, IdGenerator ids,
			Clock clock, Reassignments reassignments) {
		this.boards = boards;
		this.reassignments = reassignments;
		this.jdbc = jdbc;
		this.criteria = criteria;
		this.crews = crews;
		this.students = students;
		this.mentors = mentors;
		this.check = check;
		this.notifications = notifications;
		this.held = held;
		this.ids = ids;
		this.clock = clock;
	}

	@Transactional
	Item create(BoardViewer writer, Draft draft, Resolution resolution) {
		UUID jobId = writer.board().jobId();
		requireMilestone(jobId, draft.milestoneId());
		if (!writer.mentor() && (draft.priority() != null || draft.assigneeId() != null)) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.MENTOR_ONLY);
		}
		requireAssignee(jobId, draft.assigneeId());
		Draft checked = checked(draft, resolution);
		UUID id = this.ids.newId();
		int number = this.boards.nextNumber(jobId);
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, checklist, milestone_id, priority,
				    estimate_hours, assignee_account_id, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, :number, :type, :title, :description, CAST(:checklist AS JSONB), :milestone, :priority,
				    :estimate, :assignee, :creator, :now, :now)
				""")
			.param("id", id)
			.param("job", jobId)
			.param("number", number)
			.param("type", checked.type().name())
			.param("title", checked.title())
			.param("description", checked.description())
			.param("checklist", Boards.JSON.writeValueAsString(checked.checklist()))
			.param("milestone", checked.milestoneId())
			.param("priority", checked.priority() == null ? null : checked.priority().name())
			.param("estimate", checked.estimateHours())
			.param("assignee", checked.assigneeId())
			.param("creator", writer.accountId())
			.param("now", now)
			.update();
		Map<String, Object> changes = new LinkedHashMap<>();
		Item none = new Item(id, jobId, new WorkItemKey(writer.board().code(), number), checked.type(), "",
				"", List.of(), null, null, Boards.Status.TODO, null, null, null, writer.accountId(), 1, now.toInstant());
		diff(none, checked, changes);
		UUID entry = this.boards.history(id, jobId, writer.accountId(), "CREATED", changes, checked.assigneeId());
		Item created = this.boards.item(id).orElseThrow();
		if (checked.assigneeId() != null) {
			tellAssignee(writer, created, entry);
		}
		return created;
	}

	@Transactional
	Item update(BoardViewer writer, UUID itemId, long version, Draft draft, Resolution resolution) {
		Item item = this.boards.lockItem(itemId)
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (item.version() != version) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (item.seeded() && (draft.type() != item.type() || !Objects.equals(draft.milestoneId(), item.milestoneId()))) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.SEEDED_FIELD_FIXED);
		}
		if (!item.seeded()) {
			requireMilestone(item.jobId(), draft.milestoneId());
		}
		boolean assigneeChanged = !Objects.equals(draft.assigneeId(), item.assigneeId());
		if (!writer.mentor() && (assigneeChanged || draft.priority() != item.priority())) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.MENTOR_ONLY);
		}
		if (assigneeChanged) {
			requireAssignee(item.jobId(), draft.assigneeId());
		}
		Draft checked = checked(draft, resolution);
		Map<String, Object> changes = new LinkedHashMap<>();
		diff(item, checked, changes);
		if (changes.isEmpty()) {
			return item;
		}
		this.jdbc.sql("""
				UPDATE work.work_item SET type = :type, title = :title, description = :description,
				    checklist = CAST(:checklist AS JSONB), milestone_id = :milestone, priority = :priority,
				    estimate_hours = :estimate, assignee_account_id = :assignee, version = version + 1, updated_at = :now
				WHERE id = :id
				""")
			.param("id", itemId)
			.param("type", checked.type().name())
			.param("title", checked.title())
			.param("description", checked.description())
			.param("checklist", Boards.JSON.writeValueAsString(checked.checklist()))
			.param("milestone", checked.milestoneId())
			.param("priority", checked.priority() == null ? null : checked.priority().name())
			.param("estimate", checked.estimateHours())
			.param("assignee", checked.assigneeId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		UUID entry = this.boards.history(itemId, item.jobId(), writer.accountId(), "UPDATED", changes,
				assigneeChanged ? checked.assigneeId() : null);
		Item updated = this.boards.item(itemId).orElseThrow();
		if (assigneeChanged && checked.assigneeId() != null) {
			tellAssignee(writer, updated, entry);
		}
		if (assigneeChanged && item.assigneeId() != null) {
			// Story 11.4: reassigning a locked member's last open Work Item closes the Mentor's to-do.
			this.reassignments.assigneeChanged(item.jobId(), writer.mentorId(), item.assigneeId());
		}
		return updated;
	}

	/**
	 * A Board move between "Cần làm" and "Đang làm" (FR-24, AD-39): by the Mentor or the assignee, made on
	 * {@code version}, leaving "Cần làm" only while the Milestone is held; every other move is refused
	 * until submission, approval, and return arrive with their own commands.
	 */
	@Transactional
	Item move(BoardViewer writer, UUID itemId, long version, Status to) {
		Item item = this.boards.lockItem(itemId)
			.filter(found -> found.jobId().equals(writer.board().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (item.version() != version) {
			throw new ApiException(ErrorCode.STALE);
		}
		if (!writer.mentor() && !writer.accountId().equals(item.assigneeId())) {
			throw new ApiException(ErrorCode.FORBIDDEN, WorkReason.NOT_ASSIGNEE);
		}
		if (to == item.status()) {
			return item;
		}
		boolean board = (item.status() == Status.TODO && to == Status.IN_PROGRESS)
				|| (item.status() == Status.IN_PROGRESS && to == Status.TODO);
		if (!board) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.MOVE_NOT_ALLOWED);
		}
		if (item.status() == Status.TODO
				&& (item.milestoneId() == null || !this.held.heldOf(item.jobId()).contains(item.milestoneId()))) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.MILESTONE_NOT_HELD);
		}
		this.jdbc.sql("UPDATE work.work_item SET status = :to, version = version + 1, updated_at = :now WHERE id = :id")
			.param("id", itemId)
			.param("to", to.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		Map<String, Object> changes = new LinkedHashMap<>();
		change(changes, "status", item.status().name(), to.name());
		this.boards.history(itemId, item.jobId(), writer.accountId(), "MOVED", changes, null);
		return this.boards.item(itemId).orElseThrow();
	}

	private void requireMilestone(UUID jobId, UUID milestoneId) {
		boolean known = this.criteria.inForce(jobId)
			.map(inForce -> inForce.milestones().stream().map(MilestoneInForce::milestoneId).anyMatch(milestoneId::equals))
			.orElse(false);
		if (!known) {
			throw new ApiException(ErrorCode.VALIDATION, WorkReason.MILESTONE_UNKNOWN);
		}
	}

	private void requireAssignee(UUID jobId, @Nullable UUID assignee) {
		if (assignee == null) {
			return;
		}
		boolean active = this.crews.crewRelationship(jobId, assignee).kind() == Relationship.ACTIVE;
		boolean unlocked = this.students.cards(List.of(assignee)).stream().anyMatch(StudentCard::available);
		if (!active || !unlocked) {
			throw new ApiException(ErrorCode.GUARD_FAILED, WorkReason.NOT_CREW_MEMBER);
		}
	}

	/** The draft after FR-47: refused with the matches under {@code CHECK}, masked under {@code MASK}. */
	private Draft checked(Draft draft, Resolution resolution) {
		List<MatchView> matches = new ArrayList<>();
		String title = this.check.masked(draft.title(), matches);
		String description = this.check.masked(draft.description(), matches);
		List<ChecklistLine> checklist = new ArrayList<>();
		for (ChecklistLine line : draft.checklist()) {
			checklist.add(new ChecklistLine(this.check.masked(line.text(), matches), line.done()));
		}
		if (matches.isEmpty()) {
			return draft;
		}
		WorkTextCheck.requireResolved(matches, resolution);
		return new Draft(draft.type(), title, description, checklist, draft.milestoneId(), draft.priority(),
				draft.estimateHours(), draft.assigneeId());
	}

	/** Each field the save changes, with its old and new value, for the history (AD-39). */
	private static void diff(Item before, Draft after, Map<String, Object> changes) {
		change(changes, "type", before.type().name(), after.type().name());
		change(changes, "title", before.title(), after.title());
		change(changes, "description", before.description(), after.description());
		change(changes, "checklist", before.checklist(), after.checklist());
		change(changes, "milestoneId", text(before.milestoneId()), text(after.milestoneId()));
		change(changes, "priority", before.priority() == null ? null : before.priority().name(),
				after.priority() == null ? null : after.priority().name());
		change(changes, "estimateHours", before.estimateHours(), after.estimateHours());
		change(changes, "assigneeId", text(before.assigneeId()), text(after.assigneeId()));
	}

	private static void change(Map<String, Object> changes, String field, @Nullable Object from, @Nullable Object to) {
		boolean same = from instanceof BigDecimal a && to instanceof BigDecimal b ? a.compareTo(b) == 0 : Objects.equals(from, to);
		if (!same) {
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("from", from);
			change.put("to", to);
			changes.put(field, change);
		}
	}

	private static @Nullable String text(@Nullable UUID id) {
		return id == null ? null : id.toString();
	}

	/** Tells the new assignee; the history entry that assigned them is the notification's cause. */
	private void tellAssignee(BoardViewer writer, Item item, UUID entry) {
		String mentor = this.mentors.cards(List.of(writer.mentorId())).stream().findFirst().map(MentorCard::displayName)
			.orElse("");
		this.notifications.send(new NotificationRequest(ASSIGNED, entry,
				List.of(new Recipient(Objects.requireNonNull(item.assigneeId()), Audience.CREW_ACTIVE)),
				Map.of("mentor", mentor, "key", item.key().toString()), "/cong-viec/" + item.jobId() + "/bang", item.jobId(),
				null));
	}

}
