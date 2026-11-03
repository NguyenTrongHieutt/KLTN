package vn.vibefix.work.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.shared.AdminReadGrant;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.work.WorkEvidence;

/** {@link WorkEvidence} over {@code work.work_item} and {@code work.page}. */
@Component
class BoardEvidence implements WorkEvidence {

	/** A Work Item's row, with who it involves so far: its assignee, its history's actors and assignees. */
	private record Row(UUID id, String key, String title, String status, UUID assigneeId, Set<UUID> involved,
			List<HistoryLine> history) {
	}

	private final JdbcClient jdbc;

	private final WorkGrants grants;

	private final AuditLog auditLog;

	private final Clock clock;

	BoardEvidence(JdbcClient jdbc, WorkGrants grants, AuditLog auditLog, Clock clock) {
		this.jdbc = jdbc;
		this.grants = grants;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ItemLine> workItems(UUID jobId) {
		return this.jdbc.sql("SELECT id, number, version, status FROM work.work_item WHERE job_id = :job ORDER BY number")
			.param("job", jobId)
			.query((rs, n) -> new ItemLine(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getLong("version"),
					rs.getString("status")))
			.list();
	}

	@Override
	@Transactional
	public List<PartyItem> milestoneItemsUnderGrant(UUID adminId, UUID grantId, UUID jobId, UUID milestoneId) {
		Instant now = this.clock.instant();
		List<AdminReadGrant> open = this.grants.grants(adminId, jobId)
			.stream()
			.filter(grant -> grant.grantId().equals(grantId))
			.filter(grant -> grant.endsAt() == null || now.isBefore(grant.endsAt()))
			.filter(grant -> grant.items().stream().anyMatch(item -> item.scope() == AdminReadScope.WORK_ITEMS))
			.toList();
		if (open.isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		List<PartyItem> items = rows(jobId, milestoneId).stream()
			.filter(row -> WorkGrants.permits(open, adminId, jobId, row.id(), row.involved(), now))
			.map(row -> new PartyItem(row.id(), row.key(), row.title(), row.status(), row.assigneeId(), row.history()))
			.toList();
		this.auditLog.record(AuditEntry.of(AuditActor.user(adminId), "work.admin_read", AuditObject.of("work.board", jobId))
			.forJob(jobId));
		return items;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ItemLine> milestoneItems(UUID jobId, UUID milestoneId, List<UUID> accounts) {
		Map<UUID, Set<UUID>> involved = new HashMap<>();
		rows(jobId, milestoneId).forEach(row -> involved.put(row.id(), row.involved()));
		return this.jdbc.sql("""
				SELECT id, number, version, status FROM work.work_item WHERE job_id = :job AND milestone_id = :milestone
				ORDER BY number
				""")
			.param("job", jobId)
			.param("milestone", milestoneId)
			.query((rs, n) -> new ItemLine(rs.getObject("id", UUID.class), rs.getInt("number"), rs.getLong("version"),
					rs.getString("status")))
			.list()
			.stream()
			.filter(item -> involved.getOrDefault(item.workItemId(), Set.of()).stream().anyMatch(accounts::contains))
			.toList();
	}

	private List<Row> rows(UUID jobId, UUID milestoneId) {
		Map<UUID, List<HistoryLine>> histories = new HashMap<>();
		Map<UUID, Set<UUID>> involved = new HashMap<>();
		this.jdbc.sql("""
				SELECT h.work_item_id, h.action, h.actor_account_id, h.assigned_to, h.comment, h.at FROM work.work_item_history h
				JOIN work.work_item w ON w.id = h.work_item_id
				WHERE w.job_id = :job AND w.milestone_id = :milestone ORDER BY h.seq
				""").param("job", jobId).param("milestone", milestoneId).query(rs -> {
			UUID item = rs.getObject("work_item_id", UUID.class);
			UUID actor = rs.getObject("actor_account_id", UUID.class);
			UUID assigned = rs.getObject("assigned_to", UUID.class);
			histories.computeIfAbsent(item, id -> new ArrayList<>())
				.add(new HistoryLine(rs.getString("action"), actor, rs.getString("comment"), rs.getTimestamp("at").toInstant()));
			Set<UUID> people = involved.computeIfAbsent(item, id -> new HashSet<>());
			if (actor != null) {
				people.add(actor);
			}
			if (assigned != null) {
				people.add(assigned);
			}
		});
		return this.jdbc.sql("""
				SELECT w.id, b.code, w.number, w.title, w.status, w.assignee_account_id FROM work.work_item w
				JOIN work.board b ON b.job_id = w.job_id
				WHERE w.job_id = :job AND w.milestone_id = :milestone ORDER BY w.number
				""").param("job", jobId).param("milestone", milestoneId).query((rs, n) -> {
			UUID id = rs.getObject("id", UUID.class);
			UUID assignee = rs.getObject("assignee_account_id", UUID.class);
			Set<UUID> people = new HashSet<>(involved.getOrDefault(id, Set.of()));
			if (assignee != null) {
				people.add(assignee);
			}
			return new Row(id, rs.getString("code") + "-" + rs.getInt("number"), rs.getString("title"), rs.getString("status"),
					assignee, people, histories.getOrDefault(id, List.of()));
		}).list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<PageLine> pages(UUID jobId) {
		return this.jdbc.sql("SELECT id, version_no FROM work.page WHERE job_id = :job ORDER BY seq")
			.param("job", jobId)
			.query((rs, n) -> new PageLine(rs.getObject("id", UUID.class), rs.getInt("version_no")))
			.list();
	}

}
