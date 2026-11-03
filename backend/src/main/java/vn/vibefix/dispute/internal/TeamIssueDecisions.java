package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.TeamIssueEndings.Outcome;
import vn.vibefix.dispute.internal.TeamIssues.Ending;
import vn.vibefix.dispute.internal.TeamIssues.Phase;
import vn.vibefix.dispute.internal.TeamIssues.TeamIssue;
import vn.vibefix.identity.AdministratorStepUp;
import vn.vibefix.job.TeamIssueLocks;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * An Administrator decides an escalated Team Issue (FR-63, FR-79, AD-20, AD-40). "Nhận xử lý" records the
 * assigned Administrator and pushes their read grant (the Crew Chat and the raiser's Work Item histories; the Rating
 * itself is read through {@code reputation}) to every module holding that content; taking it over ends the previous
 * grant at once. The assignee's decision, keeping or removing the Rating with written reasoning and after the step-up,
 * is recorded and final, and ends the issue (ending 3) through {@link TeamIssueEndings}, which executes it exactly and
 * ends the grant 30 days after the decision.
 */
@Component
class TeamIssueDecisions {

	/** What a decision answers: decided, or refused by the step-up. */
	sealed interface Decision {

		record Decided(TeamIssue issue) implements Decision {
		}

		record Refused(ApiException refusal, boolean endSession) implements Decision {
		}

	}

	/** The record of a decision, kept with the issue (NFR-10, SG-27). */
	record Recorded(@Nullable UUID adminId, @Nullable Boolean ratingRemoved, String reasoning, Instant decidedAt) {
	}

	private final JdbcClient jdbc;

	private final TeamIssues issues;

	private final TeamIssueEndings endings;

	private final TeamIssueLocks locks;

	private final List<AdminReadGrants> grants;

	private final AdministratorStepUp stepUp;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final TransactionTemplate transactions;

	private final TakeConflicts conflicts;

	TeamIssueDecisions(JdbcClient jdbc, TeamIssues issues, TeamIssueEndings endings, TeamIssueLocks locks,
			List<AdminReadGrants> grants, AdministratorStepUp stepUp, AuditLog auditLog, IdGenerator ids, Clock clock,
			PlatformTransactionManager transactionManager, TakeConflicts conflicts) {
		this.conflicts = conflicts;
		this.transactions = new TransactionTemplate(transactionManager);
		this.jdbc = jdbc;
		this.issues = issues;
		this.endings = endings;
		this.locks = locks;
		this.grants = grants;
		this.stepUp = stepUp;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * "Nhận xử lý": the Administrator takes the issue, over another if need be.
	 * @throws ApiException {@code NOT_FOUND} for an issue never escalated; {@code CONFLICT ALREADY_DECIDED}
	 * once it ended; {@code GUARD_FAILED CONFLICT_OF_INTEREST}
	 */
	@Transactional
	void take(UUID issueId, UUID adminId) {
		TeamIssue found = escalated(issueId);
		this.locks.lockForTeamIssue(found.jobId());
		TeamIssue issue = this.issues.lock(found.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (issue.phase() != Phase.ESCALATED || decision(issueId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_DECIDED);
		}
		Map<String, Object> current = this.jdbc
			.sql("SELECT assigned_admin_id, grant_id FROM dispute.team_issue WHERE id = :id")
			.param("id", issueId)
			.query((rs, n) -> {
				HashMap<String, Object> row = new HashMap<>();
				row.put("admin", rs.getObject("assigned_admin_id", UUID.class));
				row.put("grant", rs.getObject("grant_id", UUID.class));
				return row;
			})
			.single();
		if (adminId.equals(current.get("admin"))) {
			return;
		}
		this.conflicts.refuseIfLinked(adminId, this.conflicts.involvedInJob(issue.jobId()), "dispute.team_issue", issueId,
				issue.jobId());
		Instant now = this.clock.instant();
		UUID previous = (UUID) current.get("grant");
		if (previous != null) {
			this.grants.forEach(module -> module.endAdminRead(previous, now));
		}
		UUID grant = this.ids.newId();
		Set<AdminReadItem> items = issue.raiserId() == null
				? Set.of(AdminReadItem.all(AdminReadScope.CREW_CHAT))
				: AdminReadItem.forEscalatedTeamIssue(issue.raiserId());
		this.grants.forEach(module -> module.grantAdminRead(grant, adminId, issue.jobId(), items, issueId));
		this.jdbc.sql("UPDATE dispute.team_issue SET assigned_admin_id = :admin, assigned_at = :now, grant_id = :grant WHERE id = :id")
			.param("admin", adminId)
			.param("now", Timestamp.from(now))
			.param("grant", grant)
			.param("id", issueId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(adminId), "dispute.team_issue_taken",
				AuditObject.of("dispute.team_issue", issueId)).forJob(issue.jobId()));
	}

	/**
	 * The assignee's decision: whether the Rating is removed.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_ASSIGNED} (SG-31); {@code CONFLICT ALREADY_DECIDED};
	 * {@code VALIDATION}
	 */
	Decision decide(UUID issueId, UUID adminId, @Nullable Boolean removeRating, String reasoning, String stepUpCode) {
		Decision.Refused refused = this.transactions
			.execute(status -> record(issueId, adminId, removeRating, reasoning, stepUpCode));
		if (refused != null) {
			return refused;
		}
		this.endings.end(issueId, adminId, Ending.DECIDED, issue -> {
			if (issue.phase() != Phase.ESCALATED) {
				return null;
			}
			Recorded decided = decision(issueId).orElseThrow();
			return new Outcome(Boolean.TRUE.equals(decided.ratingRemoved()));
		});
		return new Decision.Decided(this.issues.find(issueId).orElseThrow());
	}

	/**
	 * In the caller's transaction: checks the decision under the locks, passes the step-up, and records it.
	 * @return the step-up's refusal, or {@code null} once recorded
	 */
	private Decision.@Nullable Refused record(UUID issueId, UUID adminId, @Nullable Boolean removeRating, String reasoning,
			String stepUpCode) {
		TeamIssue found = escalated(issueId);
		this.locks.lockForTeamIssue(found.jobId());
		TeamIssue issue = this.issues.lock(found.id()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!adminId.equals(assignee(issueId))) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.NOT_ASSIGNED);
		}
		if (issue.phase() != Phase.ESCALATED || decision(issueId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_DECIDED);
		}
		String text = reasoning.strip();
		if (text.isEmpty() || text.length() > 4000 || removeRating == null) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		AdministratorStepUp.Result check = this.stepUp.check(adminId, stepUpCode);
		if (!check.passed()) {
			return new Decision.Refused(check.refusal(), check.endSession());
		}
		this.jdbc.sql("""
				UPDATE dispute.team_issue SET decided_by_admin_id = :admin, decision_rating_removed = :removed,
				    decision_reasoning = :reasoning, decided_at = :now
				WHERE id = :id
				""")
			.param("admin", adminId)
			.param("removed", removeRating)
			.param("reasoning", text)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", issueId)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.user(adminId), "dispute.team_issue_decided",
				AuditObject.of("dispute.team_issue", issueId)).forJob(issue.jobId()));
		return null;
	}

	/** The issue once it was escalated; one never escalated is not an Administrator's. */
	TeamIssue escalated(UUID issueId) {
		return this.issues.find(issueId)
			.filter(issue -> issue.escalatedAt() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	@Nullable UUID assignee(UUID issueId) {
		return this.jdbc.sql("SELECT assigned_admin_id FROM dispute.team_issue WHERE id = :id")
			.param("id", issueId)
			.query((rs, n) -> rs.getObject("assigned_admin_id", UUID.class))
			.optional()
			.orElse(null);
	}

	Optional<Recorded> decision(UUID issueId) {
		return this.jdbc.sql("""
				SELECT decided_by_admin_id, decision_rating_removed, decision_reasoning, decided_at
				FROM dispute.team_issue WHERE id = :id AND decided_at IS NOT NULL
				""").param("id", issueId).query((rs, n) -> new Recorded(rs.getObject("decided_by_admin_id", UUID.class),
				rs.getObject("decision_rating_removed", Boolean.class), rs.getString("decision_reasoning"),
				rs.getTimestamp("decided_at").toInstant())).optional();
	}

}
