package vn.vibefix.work.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.notification.Todos;

/**
 * The open Work Items of a member who left, or whose lock counted as leaving (FR-59, FR-82, FR-23, AD-41, Stories 20.4
 * and 21.6): every Work Item not Done assigned to the account on a Board whose Job is Active or Closing becomes unassigned
 * for the Mentor to assign again, each change an ordinary history entry by vibefix, and their
 * {@code work.fix_returned_item} to-do closes; a Board of a Job that ended stays as it was. Nothing reassigns, and no
 * {@code work.reassign_work_items} to-do opens any more; one opened before Story 20.4 closes once nothing of that
 * person's is open, at the unassignment or at a reassignment. The Mentor reads who left while such a Work Item waits.
 */
@Component
class Reassignments {

	static final String TODO = "work.reassign_work_items";

	/** A Job with a Board whose Work Items can still move. */
	private static final Set<String> BOARD_STATES = Set.of("ACTIVE", "CLOSING");

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final Crews crews;

	private final Boards boards;

	private final Todos todos;

	private final Clock clock;

	Reassignments(JdbcClient jdbc, JobPosts posts, Crews crews, Boards boards, Todos todos, Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.crews = crews;
		this.boards = boards;
		this.todos = todos;
		this.clock = clock;
	}

	/** A former member still named by an open, unassigned Work Item of the Job, and when they left. */
	record Leaver(UUID accountId, Instant leftAt) {
	}

	/**
	 * The account was locked: each of its open Work Items on a Board that can still move loses its assignee, with a
	 * history entry and a new version. Safe to replay: an unassigned item changes nothing.
	 */
	@Transactional
	void unassign(UUID accountId) {
		Map<UUID, UUID> items = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT id, job_id FROM work.work_item WHERE assignee_account_id = :id AND status <> 'DONE'
				ORDER BY job_id, number FOR UPDATE
				""").param("id", accountId).query(rs -> {
			items.put(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class));
		});
		unassign(accountId, items);
	}

	/** The member left the Job, or a lock counted as leaving (Story 21.6): as {@link #unassign(UUID)}, on that Job only. */
	@Transactional
	void unassign(UUID jobId, UUID accountId) {
		Map<UUID, UUID> items = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT id FROM work.work_item WHERE job_id = :job AND assignee_account_id = :id AND status <> 'DONE'
				ORDER BY number FOR UPDATE
				""").param("job", jobId).param("id", accountId).query(rs -> {
			items.put(rs.getObject("id", UUID.class), jobId);
		});
		unassign(accountId, items);
	}

	/** {@code items}, each with its Job, lose their assignee where the Board can still move. */
	private void unassign(UUID accountId, Map<UUID, UUID> items) {
		Map<UUID, Boolean> moving = new HashMap<>();
		Timestamp now = Timestamp.from(this.clock.instant());
		items.forEach((itemId, jobId) -> {
			if (!moving.computeIfAbsent(jobId, this::moves)) {
				return;
			}
			this.jdbc.sql("""
					UPDATE work.work_item SET assignee_account_id = NULL, version = version + 1, updated_at = :now WHERE id = :id
					""").param("id", itemId).param("now", now).update();
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("from", accountId.toString());
			change.put("to", null);
			Map<String, Object> changes = new LinkedHashMap<>();
			changes.put("assigneeId", change);
			this.boards.history(itemId, jobId, null, "UPDATED", changes, null);
			this.todos.close(Reviews.FIX_TODO, accountId, itemId);
		});
		moving.forEach((jobId, moves) -> {
			if (moves) {
				this.todos.closeAll(TODO, subject(jobId, accountId));
			}
		});
	}

	/** After an assignee change: a to-do opened for the earlier assignee closes once nothing of theirs is open. */
	void assigneeChanged(UUID jobId, UUID mentorId, UUID previous) {
		long open = this.jdbc.sql("""
				SELECT count(*) FROM work.work_item WHERE job_id = :job AND assignee_account_id = :id AND status <> 'DONE'
				""").param("job", jobId).param("id", previous).query(Long.class).single();
		if (open == 0) {
			this.todos.close(TODO, mentorId, subject(jobId, previous));
		}
	}

	/**
	 * The former members whose open Work Items vibefix unassigned and nobody has assigned again, for the Mentor's line
	 * "{name} đã rời công việc ngày {date}" (UX, Story 21.6), the same for a leave and a lock: a Work Item the Mentor
	 * unassigned themself names nobody.
	 */
	List<Leaver> leavers(UUID jobId) {
		Set<String> named = new HashSet<>(this.jdbc.sql("""
				SELECT DISTINCT latest.change ->> 'from' FROM work.work_item w
				CROSS JOIN LATERAL (SELECT h.actor_account_id AS actor, h.changes -> 'assigneeId' AS change
				    FROM work.work_item_history h WHERE h.work_item_id = w.id AND h.changes -> 'assigneeId' IS NOT NULL
				    ORDER BY h.seq DESC LIMIT 1) latest
				WHERE w.job_id = :job AND w.status <> 'DONE' AND w.assignee_account_id IS NULL AND latest.actor IS NULL
				  AND latest.change ->> 'from' IS NOT NULL
				""").param("job", jobId).query(String.class).list());
		if (named.isEmpty()) {
			return List.of();
		}
		return this.crews.crewOf(jobId, Selection.EVER_ACTIVE)
			.stream()
			.filter(member -> member.windowEnd() != null && "RELEASED".equals(member.status())
					&& named.contains(member.studentId().toString()))
			.map(member -> new Leaver(member.studentId(), member.windowEnd()))
			.toList();
	}

	/** Whether the Job's Board can still move: the Job is Active or Closing. */
	private boolean moves(UUID jobId) {
		return this.posts.post(jobId).map(JobPostState::state).filter(BOARD_STATES::contains).isPresent();
	}

	private static UUID subject(UUID jobId, UUID accountId) {
		return UUID.nameUUIDFromBytes((jobId + ":reassign:" + accountId).getBytes(StandardCharsets.UTF_8));
	}

}
