package vn.vibefix.dispute.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.internal.SplitResolutions.Resolution;
import vn.vibefix.identity.AdministratorStepUp;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.MilestoneSplits.CaseParty;
import vn.vibefix.job.MilestoneSplits.PercentLine;
import vn.vibefix.job.MilestoneSplits.PreviewBasis;
import vn.vibefix.job.MilestoneSplits.SplitCase;
import vn.vibefix.payment.SplitPools;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * An Administrator takes and decides a Split Resolution (FR-84, AD-20, AD-24, AD-29, AD-40, SG-10). "Nhận xử lý" records the
 * assignee and pushes their grant, the split's versions, the Crew Chat and every party's Work Item histories, never the Job
 * Chat, to every module holding that content; a takeover ends the old grant at once. The assignee's decision, with written
 * reasoning and after the step-up, saves the {@code DECISION} version under {@code job.decideSplit}, checked against the cap
 * stored on the latest version, and is final: {@code payment}'s listener alone moves the pool. Its record keeps the decision,
 * the reasoning, the VND amounts, and the IDs and versions of the evidence read; the grant ends 30 days later.
 */
@Component
class SplitResolutionDecisions {

	/** How long the assignee still reads the evidence after the decision (AD-20). */
	static final Duration GRANT_AFTER_DECISION = Duration.ofDays(30);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** What a decision answers: decided, or refused by the step-up, thrown after commit so the failure counts. */
	sealed interface Outcome {

		record Decided() implements Outcome {
		}

		record Refused(ApiException refusal, boolean endSession) implements Outcome {
		}

	}

	/** One party's line of a decision as its record keeps it. */
	record DecisionLine(UUID partyId, int percent, long amountVnd) {
	}

	/** A decision's VND amounts as its record keeps them. */
	record DecisionAmounts(long pooledVnd, List<DecisionLine> lines) {
	}

	/** A decision as its record keeps it. */
	record Recorded(String reasoning, Instant decidedAt, int versionNo, DecisionAmounts amounts,
			SplitEvidenceSnapshots.SplitEvidenceSnapshot snapshot) {
	}

	private final JdbcClient jdbc;

	private final SplitResolutions resolutions;

	private final MilestoneSplits splits;

	private final SplitPools pools;

	private final SplitEvidenceSnapshots snapshots;

	private final TakeConflicts conflicts;

	private final List<AdminReadGrants> grants;

	private final AdministratorStepUp stepUp;

	private final SplitResolutionPeople people;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	SplitResolutionDecisions(JdbcClient jdbc, SplitResolutions resolutions, MilestoneSplits splits, SplitPools pools,
			SplitEvidenceSnapshots snapshots, TakeConflicts conflicts, List<AdminReadGrants> grants, AdministratorStepUp stepUp,
			SplitResolutionPeople people, AuditLog auditLog, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.resolutions = resolutions;
		this.splits = splits;
		this.pools = pools;
		this.snapshots = snapshots;
		this.conflicts = conflicts;
		this.grants = grants;
		this.stepUp = stepUp;
		this.people = people;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * "Nhận xử lý": the Administrator takes the case, over another if need be; the assignee taking it again changes nothing.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT ALREADY_DECIDED}; {@code GUARD_FAILED} with
	 * {@code SPLIT_RESOLUTION_ENDED} once withdrawn, or {@code CONFLICT_OF_INTEREST}
	 */
	@Transactional
	void take(UUID resolutionId, UUID adminId) {
		Resolution resolution = this.resolutions.lockedResolution(resolutionId);
		refuseEnded(resolution);
		if (adminId.equals(resolution.assigneeId()) && resolution.grantId() != null) {
			return;
		}
		SplitCase split = this.splits.caseOf(resolution.splitId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		refuseIfLinked(adminId, resolution, split);
		Instant now = this.clock.instant();
		UUID previous = resolution.grantId();
		if (previous != null) {
			this.grants.forEach(module -> module.endAdminRead(previous, now));
		}
		UUID grant = this.ids.newId();
		Set<AdminReadItem> items = AdminReadItem.forSplitResolution(resolution.splitId(), partyAccounts(split));
		this.grants.forEach(module -> module.grantAdminRead(grant, adminId, resolution.jobId(), items, resolutionId));
		this.jdbc.sql("UPDATE dispute.split_resolution SET assigned_admin_id = :admin, assigned_at = :now, grant_id = :grant WHERE id = :id")
			.param("admin", adminId)
			.param("now", Timestamp.from(now))
			.param("grant", grant)
			.param("id", resolutionId)
			.update();
		audit(adminId, "dispute.split_resolution_taken", resolution);
	}

	/**
	 * The VND lines of the percentages typed, for the assignee only: {@code payment} computes them on the pool.
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_ASSIGNED}; {@code CONFLICT ALREADY_DECIDED};
	 * {@code GUARD_FAILED SPLIT_RESOLUTION_ENDED}; {@code VALIDATION SPLIT_INVALID}
	 */
	@Transactional(readOnly = true)
	SplitPools.Parts preview(UUID resolutionId, UUID adminId, List<PercentLine> lines) {
		Resolution resolution = this.resolutions.find(resolutionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireAssignee(resolution, adminId);
		refuseEnded(resolution);
		PreviewBasis basis = this.splits.decisionBasis(resolution.splitId(), lines);
		return this.pools.parts(basis.milestoneId(), basis.mentorPartyId(), basis.percents());
	}

	/**
	 * The assignee's decision, after the step-up, in one transaction: a refused one changes nothing but the step-up's count. A
	 * person-link signal recorded since the take stops it before the step-up, as it would have stopped the take (AD-19, A-17).
	 * @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_ASSIGNED} (SG-31); {@code CONFLICT ALREADY_DECIDED};
	 * {@code GUARD_FAILED} with {@code SPLIT_RESOLUTION_ENDED} or {@code CONFLICT_OF_INTEREST}; {@code VALIDATION}, with
	 * {@code SPLIT_INVALID} for the percentages
	 */
	@Transactional
	Outcome decide(UUID resolutionId, UUID adminId, List<PercentLine> lines, String reasoning, String stepUpCode) {
		Resolution resolution = this.resolutions.lockedResolution(resolutionId);
		requireAssignee(resolution, adminId);
		refuseEnded(resolution);
		String text = reasoning.strip();
		if (text.isEmpty() || text.length() > 4000) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		SplitCase split = this.splits.caseOf(resolution.splitId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		refuseIfLinked(adminId, resolution, split);
		PreviewBasis basis = this.splits.decisionBasis(resolution.splitId(), lines);
		AdministratorStepUp.Result check = this.stepUp.check(adminId, stepUpCode);
		if (!check.passed()) {
			return new Outcome.Refused(check.refusal(), check.endSession());
		}
		Instant now = this.clock.instant();
		SplitEvidenceSnapshots.SplitEvidenceSnapshot snapshot = this.snapshots.take(split, partyAccounts(split), now);
		int versionNo = this.splits.decideSplit(resolution.milestoneId(), resolutionId, adminId, lines);
		SplitPools.Parts parts = this.pools.parts(basis.milestoneId(), basis.mentorPartyId(), basis.percents());
		List<DecisionLine> decided = new ArrayList<>();
		basis.percents().forEach((party, percent) -> decided.add(new DecisionLine(party, percent,
				parts.amounts().getOrDefault(party, 0L))));
		this.jdbc.sql("""
				UPDATE dispute.split_resolution SET status = 'DECIDED', ended_at = :now, decided_by_admin_id = :admin,
				    decision_version_no = :version, decision_reasoning = :reasoning, decision_amounts = CAST(:amounts AS JSONB),
				    evidence_snapshot = CAST(:snapshot AS JSONB)
				WHERE id = :id
				""")
			.param("now", Timestamp.from(now))
			.param("admin", adminId)
			.param("version", versionNo)
			.param("reasoning", text)
			.param("amounts", JSON.writeValueAsString(new DecisionAmounts(parts.pooledVnd(), decided)))
			.param("snapshot", SplitEvidenceSnapshots.json(snapshot))
			.param("id", resolutionId)
			.update();
		UUID grant = resolution.grantId();
		if (grant != null) {
			Instant ends = now.plus(GRANT_AFTER_DECISION);
			this.grants.forEach(module -> module.endAdminRead(grant, ends));
		}
		this.resolutions.tellParties(SplitResolutions.DECIDED, resolutionId, split, SplitResolutions.partiesExcept(split, null),
				Map.of("n", String.valueOf(split.milestoneNumber()), "job", SplitResolutions.title(split)));
		audit(adminId, "dispute.split_resolution_decided", resolution);
		return new Outcome.Decided();
	}

	/**
	 * Whether {@code adminId} reads the case's evidence now: its assignee, under the grant their take opened, until 30 days
	 * after the decision or until the Job's content goes, which a final split no longer holds back; a withdrawal ended it at
	 * once.
	 */
	boolean evidenceReadable(Resolution resolution, UUID adminId) {
		if (resolution.grantId() == null || !adminId.equals(resolution.assigneeId())) {
			return false;
		}
		return switch (resolution.status()) {
			case OPEN -> true;
			case WITHDRAWN -> false;
			case DECIDED -> resolution.endedAt() != null
					&& this.clock.instant().isBefore(resolution.endedAt().plus(GRANT_AFTER_DECISION))
					&& this.splits.jobContentKept(resolution.jobId());
		};
	}

	/** A decided case whose Job's content went: its record stands, with no evidence behind it to read (AD-24). */
	boolean jobContentGone(Resolution resolution) {
		return resolution.status() == SplitResolutions.Status.DECIDED && !this.splits.jobContentKept(resolution.jobId());
	}

	/** The decision's record; empty before it. */
	@Transactional(readOnly = true)
	Optional<Recorded> recorded(UUID resolutionId) {
		return this.jdbc.sql("""
				SELECT decision_reasoning, ended_at, decision_version_no, decision_amounts::text AS amounts,
				    evidence_snapshot::text AS snapshot
				FROM dispute.split_resolution WHERE id = :id AND status = 'DECIDED'
				""")
			.param("id", resolutionId)
			.query((rs, n) -> new Recorded(rs.getString("decision_reasoning"), rs.getTimestamp("ended_at").toInstant(),
					rs.getInt("decision_version_no"), JSON.readValue(rs.getString("amounts"), DecisionAmounts.class),
					SplitEvidenceSnapshots.parse(rs.getString("snapshot"))))
			.optional();
	}

	/** The split's parties who still have an account. */
	static List<UUID> partyAccounts(SplitCase split) {
		return split.parties().stream().map(CaseParty::accountId).filter(Objects::nonNull).toList();
	}

	/** The Customer, the Mentor, the Crew and the parties (AD-19, A-17). */
	private void refuseIfLinked(UUID adminId, Resolution resolution, SplitCase split) {
		Set<@Nullable UUID> involved = new HashSet<>(this.conflicts.crewOf(split.jobId()));
		involved.add(split.customerId());
		involved.add(split.mentorId());
		split.parties().forEach(party -> involved.add(party.accountId()));
		this.conflicts.refuseIfLinked(adminId, involved, "dispute.split_resolution", resolution.id(), resolution.jobId());
	}

	private static void requireAssignee(Resolution resolution, UUID adminId) {
		if (!adminId.equals(resolution.assigneeId())) {
			throw new ApiException(ErrorCode.CONFLICT, DisputeReason.NOT_ASSIGNED);
		}
	}

	private static void refuseEnded(Resolution resolution) {
		switch (resolution.status()) {
			case OPEN -> {
			}
			case DECIDED -> throw new ApiException(ErrorCode.CONFLICT, DisputeReason.ALREADY_DECIDED);
			case WITHDRAWN -> throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.SPLIT_RESOLUTION_ENDED);
		}
	}

	private void audit(UUID adminId, String action, Resolution resolution) {
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(adminId), action, AuditObject.of("dispute.split_resolution", resolution.id()))
			.forJob(resolution.jobId()));
	}

}
