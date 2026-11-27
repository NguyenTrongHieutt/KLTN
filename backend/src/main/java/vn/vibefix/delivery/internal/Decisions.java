package vn.vibefix.delivery.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.delivery.internal.DeliveryAccess.Kind;
import vn.vibefix.delivery.internal.DeliveryAccess.Viewer;
import vn.vibefix.delivery.internal.Handovers.CodeRecord;
import vn.vibefix.delivery.internal.Handovers.Handover;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneHandovers.Decided;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The Customer's decision on a handover (FR-33, FR-34, AD-5, AD-23): "Nghiệm thu giai đoạn" applies M5
 * and has {@code payment} write the split release in the same transaction. On a Code Job it first
 * merges the Handover Commit: the command only reserves the Job and requests the merge, and
 * {@link AcceptanceMerges} applies M5 once GitHub shows it merged. "Yêu cầu sửa" names the
 * criteria marked "Chưa đạt", and applies M4, which uses one Revision Round. The Customer's comments are stored as
 * written, on either decision (D5).
 * Either keeps the Customer's marks, decides the handover once, and closes the review to-do; the
 * handover to-do reopens through the M4 listener. The Job row, which M4 and M5 lock, serializes two
 * decisions (AD-4's lock order); the second meets a Milestone no longer Submitted.
 * <p>
 * In a Self-mentored Job the Customer has a third decision, "Trả lại bàn giao" (FR-86, AD-47 exception 2, Story
 * 25.5): M3 with a written reason, which voids the handover, uses no Revision Round and is counted on the Milestone.
 */
@Component
class Decisions {

	/** The Customer's mark on one criterion. */
	record Check(UUID criterionId, boolean met, @Nullable String comment) {
	}

	private final JdbcClient jdbc;

	private final Handovers handovers;

	private final MilestoneHandovers jobs;

	private final Releases releases;

	private final Todos todos;

	private final AcceptanceMerges merges;

	private final Clock clock;

	private final AuditLog auditLog;

	Decisions(JdbcClient jdbc, Handovers handovers, MilestoneHandovers jobs, Releases releases, Todos todos,
			AcceptanceMerges merges, Clock clock, AuditLog auditLog) {
		this.auditLog = auditLog;
		this.jdbc = jdbc;
		this.handovers = handovers;
		this.jobs = jobs;
		this.releases = releases;
		this.todos = todos;
		this.merges = merges;
		this.clock = clock;
	}

	/**
	 * "Nghiệm thu giai đoạn": M5 on {@code version}, then the release; on a Code Job, the merge first.
	 * @throws ApiException {@code FORBIDDEN CUSTOMER_ONLY}; {@code STALE HANDOVER_VOIDED}; {@code CONFLICT HANDOVER_DECIDED}; M5's refusals;
	 * for a Code Job the busy marker's ({@code JOB_BUSY}, {@code STALE}, {@code CONFLICT JOB_FROZEN})
	 */
	@Transactional
	Handover accept(Viewer customer, UUID handoverId, long version, List<Check> checks) {
		Handover handover = open(customer, handoverId);
		List<Check> kept = known(handover, checks);
		CodeRecord code = this.handovers.code(handoverId).orElse(null);
		if (code != null) {
			this.merges.request(handover, code, customer.accountId(), version, kept, false);
			return handover;
		}
		Decided decided = this.jobs.accept(handover.jobId(), handover.milestoneId(), customer.accountId(), version, handoverId);
		this.releases.release(handover.jobId(), handover.milestoneId(), decided.transitionId());
		decide(handover, customer.accountId(), customer.job().customerId(), "ACCEPTED", kept, false);
		return this.handovers.find(handoverId).orElseThrow();
	}

	/**
	 * Auto-acceptance (FR-35): M5 by the System with exactly an acceptance's effects, the split release
	 * included, in the Deadline's transaction. Runs only for a handover still waiting.
	 * @return whether it applied; nothing is written when M5 refused
	 */
	@Transactional
	boolean acceptAutomatically(Handover handover, UUID customerId) {
		if (!"SUBMITTED".equals(handover.status())) {
			return false;
		}
		Decided decided = this.jobs.autoAccept(handover.jobId(), handover.milestoneId(), handover.id()).orElse(null);
		if (decided == null) {
			return false;
		}
		this.releases.release(handover.jobId(), handover.milestoneId(), decided.transitionId());
		decide(handover, null, customerId, "ACCEPTED", List.of(), true);
		return true;
	}

	/**
	 * M5 once a Code Job's merge is verified, in its resolving transaction (FR-33, AD-5): by the Customer
	 * who pressed "Nghiệm thu giai đoạn", with their marks, or by Auto-acceptance; then the release and the
	 * decision.
	 * @return whether it applied; nothing is written when M5 refused
	 */
	@Transactional
	boolean acceptMerged(Handover handover, @Nullable UUID customerAccountId, List<Check> checks, boolean auto) {
		if (!"SUBMITTED".equals(handover.status())) {
			return false;
		}
		UUID customerId = this.jobs.job(handover.jobId()).orElseThrow().customerId();
		Decided decided = this.jobs
			.acceptMerged(handover.jobId(), handover.milestoneId(), auto ? null : customerAccountId, handover.id())
			.orElse(null);
		if (decided == null) {
			return false;
		}
		this.releases.release(handover.jobId(), handover.milestoneId(), decided.transitionId());
		decide(handover, auto ? null : customerAccountId, customerId, "ACCEPTED", checks, auto);
		return true;
	}

	/**
	 * "Yêu cầu sửa ({n} tiêu chí)": M4 on {@code version}, naming the criteria marked "Chưa đạt".
	 * @throws ApiException {@code FORBIDDEN CUSTOMER_ONLY}; {@code STALE HANDOVER_VOIDED}; {@code CONFLICT HANDOVER_DECIDED};
	 * {@code VALIDATION REVISION_CRITERIA_MISSING}; M4's refusals, {@code REVISION_ROUNDS_USED} among them
	 */
	@Transactional
	Handover requestRevision(Viewer customer, UUID handoverId, long version, List<Check> checks) {
		Handover handover = open(customer, handoverId);
		List<Check> kept = known(handover, checks);
		int named = (int) kept.stream().filter(mark -> !mark.met()).count();
		if (named == 0) {
			throw new ApiException(ErrorCode.VALIDATION, DeliveryReason.REVISION_CRITERIA_MISSING);
		}
		this.jobs.requestRevision(handover.jobId(), handover.milestoneId(), customer.accountId(), version, handoverId, named);
		decide(handover, customer.accountId(), customer.job().customerId(), "REVISION_REQUESTED", kept, false);
		return this.handovers.find(handoverId).orElseThrow();
	}

	/**
	 * "Trả lại bàn giao" (FR-86): M3 on {@code version} by the Customer of a Self-mentored Job, with the reason the
	 * members will read. In this transaction the handover becomes {@code VOIDED} with who returned it and why, and the
	 * review to-do closes; M3's listeners reopen the members' handover to-do and close the pull request with the
	 * reason. Auto-acceptance and its reminders end through their generation check.
	 * @throws ApiException {@code NOT_FOUND} in a normal Job, which has no return; {@code FORBIDDEN CUSTOMER_ONLY};
	 * {@code STALE HANDOVER_VOIDED} or {@code HANDOVER_RETURNED}; {@code CONFLICT HANDOVER_DECIDED}; {@code VALIDATION} for a blank reason or one past
	 * {@value Handovers#MAX_NOTE} characters; M3's refusals, {@code HANDOVER_RETURNS_USED}, {@code JOB_BUSY} and
	 * {@code JOB_FROZEN} among them
	 */
	@Transactional
	Handover returnHandover(Viewer viewer, UUID handoverId, long version, String reason) {
		if (!viewer.job().selfMentored()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Handover handover = open(viewer, handoverId);
		String written = Handovers.note(reason);
		if (written.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		this.jobs.returnHandover(handover.jobId(), handover.milestoneId(), viewer.accountId(), version, handoverId, written);
		int returned = this.jdbc.sql("""
				UPDATE delivery.handover SET status = 'VOIDED', voided_at = :now, returned = TRUE,
				    returned_by_account_id = :by, return_reason = :reason
				WHERE id = :id AND status = 'SUBMITTED'
				""")
			.param("id", handoverId)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("by", viewer.accountId())
			.param("reason", written)
			.update();
		if (returned != 1) {
			throw new ApiException(ErrorCode.CONFLICT, DeliveryReason.HANDOVER_DECIDED);
		}
		this.todos.closeAll(Handovers.REVIEW_HANDOVER, handoverId);
		this.auditLog.record(AuditEntry
			.of(AuditActor.user(viewer.accountId()), "delivery.handover_returned", AuditObject.of("delivery.handover", handoverId))
			.forJob(handover.jobId()));
		return this.handovers.find(handoverId).orElseThrow();
	}

	/** The Customer's marks, once decided. */
	@Transactional(readOnly = true)
	List<Check> checks(UUID handoverId) {
		return this.jdbc.sql("SELECT criterion_id, met, comment FROM delivery.handover_check WHERE handover_id = :id")
			.param("id", handoverId)
			.query((rs, n) -> new Check(rs.getObject("criterion_id", UUID.class), rs.getBoolean("met"), rs.getString("comment")))
			.list();
	}

	private Handover open(Viewer customer, UUID handoverId) {
		if (customer.kind() != Kind.CUSTOMER) {
			throw new ApiException(ErrorCode.FORBIDDEN, DeliveryReason.CUSTOMER_ONLY);
		}
		Handover handover = this.handovers.find(handoverId)
			.filter(found -> found.jobId().equals(customer.job().jobId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if ("VOIDED".equals(handover.status())) {
			// FR-33, UX-DR26: an acceptance merge answered 409 and M3 voided it while the Customer was deciding; or, in a
			// Self-mentored Job, the Customer already returned it (FR-86), which the answer names apart.
			throw new ApiException(ErrorCode.STALE,
					handover.returned() ? DeliveryReason.HANDOVER_RETURNED : DeliveryReason.HANDOVER_VOIDED);
		}
		if (!"SUBMITTED".equals(handover.status())) {
			throw new ApiException(ErrorCode.CONFLICT, DeliveryReason.HANDOVER_DECIDED);
		}
		return handover;
	}

	/**
	 * The marks on the handover's own criteria, each once, with comments stripped and otherwise as written (D5).
	 * @throws ApiException {@code VALIDATION} for a criterion the handover does not hold, one marked twice, or a comment
	 * past {@value Handovers#MAX_NOTE} characters
	 */
	private List<Check> known(Handover handover, List<Check> checks) {
		Set<UUID> criteria = new HashSet<>();
		this.handovers.criteria(handover.id()).forEach(criterion -> criteria.add(criterion.criterionId()));
		List<Check> kept = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (Check mark : checks) {
			if (!criteria.contains(mark.criterionId()) || !seen.add(mark.criterionId())) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			String comment = mark.comment() == null || mark.comment().isBlank() ? null : Handovers.note(mark.comment());
			kept.add(new Check(mark.criterionId(), mark.met(), comment));
		}
		return kept;
	}

	private void decide(Handover handover, @Nullable UUID decider, UUID customerId, String status, List<Check> checks,
			boolean auto) {
		int decided = this.jdbc.sql("""
				UPDATE delivery.handover SET status = :status, decided_at = :now, decided_by = :by, auto = :auto
				WHERE id = :id AND status = 'SUBMITTED'
				""")
			.param("id", handover.id())
			.param("status", status)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("by", decider)
			.param("auto", auto)
			.update();
		if (decided != 1) {
			throw new ApiException(ErrorCode.CONFLICT, DeliveryReason.HANDOVER_DECIDED);
		}
		for (Check mark : checks) {
			this.jdbc.sql("""
					INSERT INTO delivery.handover_check (handover_id, criterion_id, met, comment) VALUES (:id, :criterion, :met, :comment)
					""")
				.param("id", handover.id())
				.param("criterion", mark.criterionId())
				.param("met", mark.met())
				.param("comment", mark.comment())
				.update();
		}
		this.todos.close(Handovers.REVIEW_HANDOVER, customerId, handover.id());
	}

}
