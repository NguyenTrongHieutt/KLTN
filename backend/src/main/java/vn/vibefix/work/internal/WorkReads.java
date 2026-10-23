package vn.vibefix.work.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.work.internal.Attachments.Attachment;
import vn.vibefix.work.internal.Boards.ChecklistLine;
import vn.vibefix.work.internal.Boards.Item;
import vn.vibefix.work.internal.Boards.Priority;
import vn.vibefix.work.internal.Boards.Status;
import vn.vibefix.work.internal.Boards.Type;
import vn.vibefix.work.internal.WorkAccess.BoardViewer;
import vn.vibefix.work.internal.WorkAccess.Kind;
import vn.vibefix.work.internal.WorkAccess.Window;

/**
 * What each viewer of a Board sees (AD-20, AD-39, AD-41): the Mentor and the active Crew everything; a
 * former member only the Work Items they were assigned or wrote a history entry on inside one of their
 * windows, each as it stood at the end of their last window (later changes are undone from the
 * history), with the history up to then; an Administrator only what an open grant covers, each read
 * audited {@code work.admin_read}.
 */
@Component
class WorkReads {

	/** Review entries: "Bị trả lại" follows the latest of them (AD-39). */
	static final Set<String> REVIEW_ACTIONS = Set.of("SUBMITTED", "APPROVED", "RETURNED");

	/**
	 * One history entry; {@code changes} maps each field to its {@code from} and {@code to}.
	 * @param comment a submission's note, a review's comment, or a note's text
	 * @param selfCheck a submission's Self-check answers
	 * @param attachmentId the Attachment an {@code ATTACHED} or {@code DETACHED} entry names
	 * @param quotes the earlier note a correction quotes
	 */
	record Entry(UUID id, UUID itemId, @Nullable UUID actor, String action, JsonNode changes, @Nullable UUID assignedTo,
			@Nullable String comment, @Nullable JsonNode selfCheck, @Nullable UUID attachmentId, @Nullable UUID quotes,
			Instant at) {
	}

	private static final String ENTRY_COLUMNS = """
			id, work_item_id, actor_account_id, action, changes::text AS changes, assigned_to, comment,
			self_check::text AS self_check, attachment_id, quotes_id, at
			""";

	private final Boards boards;

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final Clock clock;

	WorkReads(Boards boards, JdbcClient jdbc, AuditLog auditLog, Clock clock) {
		this.boards = boards;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/** The Work Items the viewer sees, in key order; an Administrator's read is audited once. */
	@Transactional
	List<Item> items(BoardViewer viewer) {
		List<Item> all = this.boards.items(viewer.board());
		return switch (viewer.kind()) {
			case MENTOR, CREW -> all;
			case FORMER -> {
				Set<UUID> touched = touched(viewer);
				Map<UUID, List<Entry>> history = history(viewer.board().jobId());
				yield all.stream()
					.filter(item -> touched.contains(item.id()))
					.map(item -> asAtCutoff(item, history.getOrDefault(item.id(), List.of()), cutoff(viewer)))
					.toList();
			}
			case ADMIN -> {
				Map<UUID, List<Entry>> history = history(viewer.board().jobId());
				List<Item> granted = all.stream().filter(item -> granted(viewer, item, history)).toList();
				audit(viewer, AuditObject.of("work.board", viewer.board().jobId()));
				yield granted;
			}
			case CUSTOMER, SHARED -> throw new ApiException(ErrorCode.NOT_FOUND);
		};
	}

	/** One Work Item the viewer sees, or {@code NOT_FOUND}; an Administrator's read is audited. */
	@Transactional
	Item item(BoardViewer viewer, Item item) {
		return switch (viewer.kind()) {
			case MENTOR, CREW -> item;
			case FORMER -> {
				if (!touched(viewer).contains(item.id())) {
					throw new ApiException(ErrorCode.NOT_FOUND);
				}
				yield asAtCutoff(item, history(viewer.board().jobId()).getOrDefault(item.id(), List.of()), cutoff(viewer));
			}
			case ADMIN -> {
				if (!granted(viewer, item, history(viewer.board().jobId()))) {
					throw new ApiException(ErrorCode.NOT_FOUND);
				}
				audit(viewer, AuditObject.of("work.work_item", item.id()));
				yield item;
			}
			case CUSTOMER, SHARED -> throw new ApiException(ErrorCode.NOT_FOUND);
		};
	}

	/** The Work Item a note belongs to. */
	@Transactional(readOnly = true)
	Optional<UUID> noteItem(UUID noteId) {
		return this.jdbc.sql("SELECT work_item_id FROM work.work_item_history WHERE id = :id AND action = 'NOTE'")
			.param("id", noteId)
			.query(UUID.class)
			.optional();
	}

	/**
	 * One note the viewer sees, with its Work Item, or {@code NOT_FOUND} (FR-69). The Mentor and the Crew see
	 * it on the Work Item's history; an Administrator needs a grant naming the note, or every note, and the
	 * read is audited. An Abuse Report's grant opens that note only, not its Work Item.
	 */
	@Transactional
	Entry note(BoardViewer viewer, Item item, UUID noteId) {
		Entry note = this.jdbc.sql("SELECT " + ENTRY_COLUMNS
				+ " FROM work.work_item_history WHERE id = :id AND work_item_id = :item AND action = 'NOTE'")
			.param("id", noteId)
			.param("item", item.id())
			.query((rs, n) -> entry(rs))
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (viewer.kind() == Kind.ADMIN) {
			List<UUID> involved = note.actor() == null ? List.of() : List.of(note.actor());
			Instant now = this.clock.instant();
			boolean granted = viewer.grants()
				.stream()
				.anyMatch(grant -> grant.permits(viewer.accountId(), viewer.board().jobId(), AdminReadScope.WORK_ITEM_NOTES,
						noteId, involved, now));
			if (!granted) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			audit(viewer, AuditObject.of("work.work_item_history", noteId));
			return note;
		}
		Item seen = item(viewer, item);
		if (historyOf(viewer, seen.id()).stream().noneMatch(entry -> entry.id().equals(noteId))) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return note;
	}

	/** Each Work Item's latest review entry the viewer sees, for "Bị trả lại" on the cards. */
	@Transactional(readOnly = true)
	Map<UUID, Entry> latestReviews(BoardViewer viewer) {
		Instant cutoff = viewer.kind() == Kind.FORMER ? cutoff(viewer) : null;
		Map<UUID, Entry> latest = new HashMap<>();
		history(viewer.board().jobId()).forEach((item, entries) -> {
			for (Entry entry : entries) {
				if (REVIEW_ACTIONS.contains(entry.action()) && (cutoff == null || !entry.at().isAfter(cutoff))) {
					latest.put(item, entry);
				}
			}
		});
		return latest;
	}

	/** The Work Item's history the viewer sees, oldest first. */
	@Transactional(readOnly = true)
	List<Entry> historyOf(BoardViewer viewer, UUID itemId) {
		List<Entry> entries = this.jdbc.sql("SELECT " + ENTRY_COLUMNS
				+ " FROM work.work_item_history WHERE work_item_id = :item ORDER BY seq")
			.param("item", itemId)
			.query((rs, n) -> entry(rs))
			.list();
		if (viewer.kind() != Kind.FORMER) {
			return entries;
		}
		Instant cutoff = cutoff(viewer);
		return entries.stream().filter(entry -> !entry.at().isAfter(cutoff)).toList();
	}

	/**
	 * The Work Item's Attachments the viewer sees, oldest first (FR-76, AD-41): those not removed, or for
	 * a former member those added by the end of their last window and not removed by then.
	 */
	List<Attachment> attachmentsOf(BoardViewer viewer, List<Attachment> all) {
		return all.stream().filter(attachment -> listed(viewer, attachment)).toList();
	}

	/**
	 * Whether the viewer, who reads the Work Item, may download the Attachment: as the list shows it,
	 * and for a granted Administrator any of the Work Item's.
	 */
	boolean sees(BoardViewer viewer, Attachment attachment) {
		return viewer.kind() == Kind.ADMIN || listed(viewer, attachment);
	}

	private static boolean listed(BoardViewer viewer, Attachment attachment) {
		return switch (viewer.kind()) {
			case MENTOR, CREW, ADMIN -> attachment.removedAt() == null;
			case FORMER -> {
				Instant cutoff = cutoff(viewer);
				yield !attachment.addedAt().isAfter(cutoff) && !attachment.removedBy(cutoff);
			}
			case CUSTOMER, SHARED -> false;
		};
	}

	private Set<UUID> touched(BoardViewer viewer) {
		StringBuilder within = new StringBuilder();
		for (int i = 0; i < viewer.windows().size(); i++) {
			within.append(i == 0 ? "" : " OR ").append("(at >= :from").append(i).append(" AND at <= :to").append(i).append(')');
		}
		JdbcClient.StatementSpec query = this.jdbc.sql("""
				SELECT DISTINCT work_item_id FROM work.work_item_history
				WHERE job_id = :job AND (actor_account_id = :me OR assigned_to = :me) AND (""" + within + ")")
			.param("job", viewer.board().jobId())
			.param("me", viewer.accountId());
		for (int i = 0; i < viewer.windows().size(); i++) {
			Window window = viewer.windows().get(i);
			query = query.param("from" + i, Timestamp.from(window.from())).param("to" + i, Timestamp.from(window.to()));
		}
		return new HashSet<>(query.query(UUID.class).list());
	}

	private static Instant cutoff(BoardViewer viewer) {
		return viewer.windows().stream().map(Window::to).max(Instant::compareTo).orElseThrow();
	}

	private Map<UUID, List<Entry>> history(UUID jobId) {
		Map<UUID, List<Entry>> byItem = new HashMap<>();
		this.jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM work.work_item_history WHERE job_id = :job ORDER BY seq")
			.param("job", jobId)
			.query((rs, n) -> entry(rs))
			.list()
			.forEach(entry -> byItem.computeIfAbsent(entry.itemId(), id -> new ArrayList<>()).add(entry));
		return byItem;
	}

	private boolean granted(BoardViewer viewer, Item item, Map<UUID, List<Entry>> history) {
		Set<UUID> involved = new HashSet<>();
		if (item.assigneeId() != null) {
			involved.add(item.assigneeId());
		}
		for (Entry entry : history.getOrDefault(item.id(), List.of())) {
			if (entry.actor() != null) {
				involved.add(entry.actor());
			}
			if (entry.assignedTo() != null) {
				involved.add(entry.assignedTo());
			}
		}
		return WorkGrants.permits(viewer.grants(), viewer.accountId(), viewer.board().jobId(), item.id(), involved,
				this.clock.instant());
	}

	private void audit(BoardViewer viewer, AuditObject object) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(viewer.accountId()), "work.admin_read", object)
			.forJob(viewer.board().jobId()));
	}

	/** The Work Item as it stood at {@code cutoff}: every later change undone, newest first. */
	static Item asAtCutoff(Item item, List<Entry> history, Instant cutoff) {
		Item state = item;
		for (int i = history.size() - 1; i >= 0; i--) {
			Entry entry = history.get(i);
			if (!entry.at().isAfter(cutoff)) {
				break;
			}
			state = undo(state, entry.changes());
		}
		return state;
	}

	private static Item undo(Item item, JsonNode changes) {
		Type type = item.type();
		String title = item.title();
		String description = item.description();
		List<ChecklistLine> checklist = item.checklist();
		UUID milestone = item.milestoneId();
		Status status = item.status();
		UUID assignee = item.assigneeId();
		Priority priority = item.priority();
		BigDecimal estimate = item.estimateHours();
		for (Map.Entry<String, JsonNode> change : changes.properties()) {
			JsonNode from = change.getValue().path("from");
			boolean none = from.isMissingNode() || from.isNull();
			switch (change.getKey()) {
				case "type" -> type = none ? type : Type.valueOf(from.asString());
				case "title" -> title = none ? title : from.asString();
				case "description" -> description = none ? "" : from.asString();
				case "checklist" -> checklist = none ? List.of()
						: Boards.JSON.convertValue(from, new TypeReference<List<ChecklistLine>>() {
						});
				case "milestoneId" -> milestone = none ? null : UUID.fromString(from.asString());
				case "status" -> status = none ? status : Status.valueOf(from.asString());
				case "assigneeId" -> assignee = none ? null : UUID.fromString(from.asString());
				case "priority" -> priority = none ? null : Priority.valueOf(from.asString());
				case "estimateHours" -> estimate = none ? null : from.decimalValue();
				default -> {
				}
			}
		}
		return new Item(item.id(), item.jobId(), item.key(), type, title, description, checklist, item.criterionId(),
				milestone, status, assignee, priority, estimate, item.createdBy(), item.version(), item.createdAt());
	}

	private static Entry entry(ResultSet rs) throws SQLException {
		String selfCheck = rs.getString("self_check");
		return new Entry(rs.getObject("id", UUID.class), rs.getObject("work_item_id", UUID.class),
				rs.getObject("actor_account_id", UUID.class), rs.getString("action"),
				Boards.JSON.readTree(rs.getString("changes")), rs.getObject("assigned_to", UUID.class),
				rs.getString("comment"), selfCheck == null ? null : Boards.JSON.readTree(selfCheck),
				rs.getObject("attachment_id", UUID.class), rs.getObject("quotes_id", UUID.class),
				rs.getTimestamp("at").toInstant());
	}

}
