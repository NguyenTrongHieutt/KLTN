package vn.vibefix.work.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.job.JobPosts;
import vn.vibefix.job.JobPosts.JobPostState;
import vn.vibefix.notification.Todos;

/**
 * A locked account's open Work Items (FR-59, FR-82, FR-23, AD-41, Story 20.4): a lock counts as leaving, so every
 * open Work Item assigned to the account on a Board whose Job is Active or Closing becomes unassigned for the Mentor
 * to assign again, each change an ordinary history entry by vibefix; a Board of a Job that ended stays as it was. No
 * {@code work.reassign_work_items} to-do opens any more; one opened before Story 20.4 closes once nothing of that
 * person's is open, at the unassignment or at a reassignment.
 */
@Component
class Reassignments {

	static final String TODO = "work.reassign_work_items";

	/** A Job with a Board whose Work Items can still move. */
	private static final Set<String> BOARD_STATES = Set.of("ACTIVE", "CLOSING");

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final Boards boards;

	private final Todos todos;

	private final Clock clock;

	Reassignments(JdbcClient jdbc, JobPosts posts, Boards boards, Todos todos, Clock clock) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.boards = boards;
		this.todos = todos;
		this.clock = clock;
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

	/** Whether the Job's Board can still move: the Job is Active or Closing. */
	private boolean moves(UUID jobId) {
		return this.posts.post(jobId).map(JobPostState::state).filter(BOARD_STATES::contains).isPresent();
	}

	private static UUID subject(UUID jobId, UUID accountId) {
		return UUID.nameUUIDFromBytes((jobId + ":reassign:" + accountId).getBytes(StandardCharsets.UTF_8));
	}

}
