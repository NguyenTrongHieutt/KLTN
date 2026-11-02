package vn.vibefix.dispute.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.PersonSignals;
import vn.vibefix.job.Crews;
import vn.vibefix.job.JobOversight;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Every Administrator's "Nhận xử lý" in {@code dispute} (AD-19, A-17): an Administrator who shares a person-link signal with an
 * account involved in the case does not take it, and another does. The check asks {@code identity} once per account, and the
 * refusal names neither the account nor the signal. An Administrator holds no role in a Job, so is never a party themselves.
 * The refusal rolls the take back, so its audit entry commits on its own.
 */
@Component
class TakeConflicts {

	private final PersonSignals signals;

	private final JobOversight jobs;

	private final Crews crews;

	private final AuditLog auditLog;

	private final TransactionTemplate own;

	TakeConflicts(PersonSignals signals, JobOversight jobs, Crews crews, AuditLog auditLog,
			PlatformTransactionManager transactionManager) {
		this.signals = signals;
		this.jobs = jobs;
		this.crews = crews;
		this.auditLog = auditLog;
		this.own = new TransactionTemplate(transactionManager);
		this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @param object the case's table, for the audit entry
	 * @throws ApiException {@code GUARD_FAILED CONFLICT_OF_INTEREST}
	 */
	void refuseIfLinked(UUID adminId, Collection<@Nullable UUID> involved, String object, UUID caseId, UUID jobId) {
		boolean linked = involved.stream()
			.filter(Objects::nonNull)
			.distinct()
			.anyMatch(account -> this.signals.sharesPersonSignal(adminId, account));
		if (!linked) {
			return;
		}
		this.own.executeWithoutResult(status -> this.auditLog.record(AuditEntry
			.of(AuditActor.user(adminId), "dispute.take_conflict_refused", AuditObject.of(object, caseId))
			.forJob(jobId)));
		throw new ApiException(ErrorCode.GUARD_FAILED, DisputeReason.CONFLICT_OF_INTEREST);
	}

	/** A Job's Customer, its Mentor, and everyone ever on its Crew or on it now. */
	Set<@Nullable UUID> involvedInJob(UUID jobId) {
		Set<@Nullable UUID> involved = new LinkedHashSet<>();
		this.jobs.job(jobId).ifPresent(job -> {
			involved.add(job.customerId());
			involved.add(job.mentorId());
		});
		involved.addAll(crewOf(jobId));
		return involved;
	}

	/** Everyone ever active on the Job's Crew, and everyone picked for it. */
	Set<UUID> crewOf(UUID jobId) {
		Set<UUID> crew = new LinkedHashSet<>();
		for (Crews.Selection selection : new Crews.Selection[] { Crews.Selection.EVER_ACTIVE, Crews.Selection.UNRELEASED }) {
			this.crews.crewOf(jobId, selection).forEach(member -> crew.add(member.studentId()));
		}
		return crew;
	}

}
