package vn.vibefix.work.internal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.CrewMember;
import vn.vibefix.job.Crews.Selection;
import vn.vibefix.job.JobPosts;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;

/**
 * A locked Crew member's open Work Items (Story 11.4; FR-59, FR-23, AD-41, UX-DR83): while the Job has
 * another active member with an unlocked account, the Mentor gets {@code work.reassign_work_items} naming
 * the member and how many Work Items wait, linked to the Board, whose banner filters by them. Each
 * reassignment is an ordinary assignee change, and the to-do closes once nothing of theirs is open. With
 * nobody left to work, the Dispute that {@code job}'s lock opens decides instead, so no to-do opens.
 */
@Component
class Reassignments {

	static final String TODO = "work.reassign_work_items";

	/** A Job with a Board whose Work Items can still move. */
	private static final Set<String> BOARD_STATES = Set.of("ACTIVE", "CLOSING");

	private final JdbcClient jdbc;

	private final JobPosts posts;

	private final Crews crews;

	private final StudentDirectory students;

	private final Todos todos;

	Reassignments(JdbcClient jdbc, JobPosts posts, Crews crews, StudentDirectory students, Todos todos) {
		this.jdbc = jdbc;
		this.posts = posts;
		this.crews = crews;
		this.students = students;
		this.todos = todos;
	}

	/** The Student's account was locked: one to-do per running Job where they still hold open Work Items. */
	@Transactional
	void accountLocked(UUID studentId) {
		Map<UUID, Long> open = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT job_id, count(*) AS n FROM work.work_item WHERE assignee_account_id = :id AND status <> 'DONE'
				GROUP BY job_id ORDER BY job_id
				""").param("id", studentId).query(rs -> {
			open.put(rs.getObject("job_id", UUID.class), rs.getLong("n"));
		});
		if (open.isEmpty()) {
			return;
		}
		String name = this.students.cards(List.of(studentId))
			.stream()
			.findFirst()
			.map(card -> card.name() != null ? card.name() : "@" + card.githubLogin())
			.orElse("");
		open.forEach((jobId, n) -> this.posts.post(jobId)
			.filter(post -> post.mentorId() != null && BOARD_STATES.contains(post.state()) && othersCanWork(jobId, studentId))
			.ifPresent(post -> this.todos.open(new TodoRequest(TODO, post.mentorId(), Audience.MENTOR, subject(jobId, studentId),
					Map.of("job", post.title(), "name", name, "n", String.valueOf(n)),
					"/cong-viec/" + jobId + "/bang", null, jobId))));
	}

	/** After an assignee change: the earlier assignee's to-do closes once nothing of theirs is open. */
	void assigneeChanged(UUID jobId, UUID mentorId, UUID previous) {
		long open = this.jdbc.sql("""
				SELECT count(*) FROM work.work_item WHERE job_id = :job AND assignee_account_id = :id AND status <> 'DONE'
				""").param("job", jobId).param("id", previous).query(Long.class).single();
		if (open == 0) {
			this.todos.close(TODO, mentorId, subject(jobId, previous));
		}
	}

	private boolean othersCanWork(UUID jobId, UUID studentId) {
		List<UUID> others = this.crews.crewOf(jobId, Selection.ACTIVE)
			.stream()
			.map(CrewMember::studentId)
			.filter(id -> !id.equals(studentId))
			.toList();
		return !others.isEmpty() && this.students.cards(others).stream().anyMatch(StudentCard::available);
	}

	private static UUID subject(UUID jobId, UUID studentId) {
		return UUID.nameUUIDFromBytes((jobId + ":reassign:" + studentId).getBytes(StandardCharsets.UTF_8));
	}

}
