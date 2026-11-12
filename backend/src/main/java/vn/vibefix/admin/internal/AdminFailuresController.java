package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.dispute.DisputeQueue;
import vn.vibefix.identity.AdministratorStepUp;
import vn.vibefix.operations.FailedOperations;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.StalledEventPublications;

/**
 * ADM-7 (FR-64, AD-6, AD-9, AD-31, SG-10): one queue of what failed for good, the failed operations
 * (retried behind the step-up), the failed Dispute executions (retried on ADM-4), and the stalled event
 * publications. Each retry is audited.
 */
@RestController
@Access(Gate.ADMINISTRATOR)
class AdminFailuresController {

	record FailedOperationView(UUID id, String type, String errorCode, int attempts, Instant failedAt,
			@Nullable UUID jobId) {
	}

	record FailedExecutionView(UUID disputeId, UUID jobId, int step, String failure, Instant failedAt) {
	}

	record StalledEventView(UUID id, String eventType, String listenerId, int attempts, Instant stalledAt) {
	}

	record FailuresView(List<FailedOperationView> operations, List<FailedExecutionView> executions,
			List<StalledEventView> stalledEvents) {
	}

	record RetryRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	private final FailedOperations operations;

	private final DisputeQueue disputes;

	private final StalledEventPublications stalled;

	private final AdministratorStepUp stepUp;

	private final AuditLog auditLog;

	private final CurrentCaller callers;

	private final TransactionTemplate transactions;

	AdminFailuresController(FailedOperations operations, DisputeQueue disputes, StalledEventPublications stalled,
			AdministratorStepUp stepUp, AuditLog auditLog, CurrentCaller callers, TransactionTemplate transactions) {
		this.operations = operations;
		this.disputes = disputes;
		this.stalled = stalled;
		this.stepUp = stepUp;
		this.auditLog = auditLog;
		this.callers = callers;
		this.transactions = transactions;
	}

	@GetMapping("/api/v1/admin/failures")
	FailuresView failures() {
		return new FailuresView(
				this.operations.findAll()
					.stream()
					.map(operation -> new FailedOperationView(operation.operationId(), operation.type(), operation.errorCode(),
							operation.attempts(), operation.failedAt(), operation.jobId()))
					.toList(),
				this.disputes.executionsFailed()
					.stream()
					.map(execution -> new FailedExecutionView(execution.disputeId(), execution.jobId(), execution.step(),
							execution.failure(), execution.failedAt()))
					.toList(),
				this.stalled.findAll()
					.stream()
					.map(publication -> new StalledEventView(publication.publicationId(), publication.eventType(),
							publication.listenerId(), publication.attempts(), publication.stalledAt()))
					.toList());
	}

	/** @throws ApiException the step-up refusal; {@code CONFLICT NOT_FAILED} */
	@PostMapping("/api/v1/admin/failures/operations/{operationId}/retry")
	FailuresView retry(@PathVariable UUID operationId, @Valid @RequestBody RetryRequest body, HttpServletRequest request) {
		UUID administrator = this.callers.get().requireAccountId();
		// The step-up's failure count commits with the refusal, so refusals are thrown after the transaction.
		Object outcome = this.transactions.execute(status -> {
			AdministratorStepUp.Result checked = this.stepUp.check(administrator, body.stepUpCode());
			if (!checked.passed()) {
				return checked;
			}
			if (!this.operations.retry(operationId)) {
				return new ApiException(ErrorCode.CONFLICT, AdminReason.NOT_FAILED);
			}
			this.auditLog.record(AuditEntry.of(AuditActor.user(administrator), "admin.operation_retried",
					AuditObject.of("operations.external_operation", operationId)));
			return Boolean.TRUE;
		});
		if (outcome instanceof AdministratorStepUp.Result refused) {
			if (refused.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw refused.refusal();
		}
		if (outcome instanceof ApiException refusal) {
			throw refusal;
		}
		return failures();
	}

}
