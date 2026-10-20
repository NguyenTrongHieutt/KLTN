package vn.vibefix.admin.internal;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.identity.AdministratorStepUp;
import vn.vibefix.operations.FailedOperations;
import vn.vibefix.scanning.FalsePositive;
import vn.vibefix.scanning.Scans;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.StalledEventPublications;

/**
 * ADM-7 (FR-64, AD-6, AD-9, AD-31, SG-10): one queue of what failed for good, the failed operations
 * (retried behind the step-up), the failed Dispute executions (retried on ADM-4), the stalled event
 * publications, and the false positives (reopened). Each retry and reopen is audited.
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

	record FalsePositiveView(UUID findingId, UUID productId, String productName, String ruleType, String filePath,
			String reason, @Nullable String markedByName, Instant markedAt) {
	}

	record FailuresView(List<FailedOperationView> operations, List<FailedExecutionView> executions,
			List<StalledEventView> stalledEvents, List<FalsePositiveView> falsePositives) {
	}

	record RetryRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	static final int FALSE_POSITIVES = 100;

	private final FailedOperations operations;

	private final DisputeQueue disputes;

	private final StalledEventPublications stalled;

	private final Scans scans;

	private final AccountAdministration accounts;

	private final AdministratorStepUp stepUp;

	private final AuditLog auditLog;

	private final CurrentCaller callers;

	private final TransactionTemplate transactions;

	AdminFailuresController(FailedOperations operations, DisputeQueue disputes, StalledEventPublications stalled,
			Scans scans, AccountAdministration accounts, AdministratorStepUp stepUp, AuditLog auditLog,
			CurrentCaller callers, TransactionTemplate transactions) {
		this.operations = operations;
		this.disputes = disputes;
		this.stalled = stalled;
		this.scans = scans;
		this.accounts = accounts;
		this.stepUp = stepUp;
		this.auditLog = auditLog;
		this.callers = callers;
		this.transactions = transactions;
	}

	@GetMapping("/api/v1/admin/failures")
	FailuresView failures() {
		List<FalsePositive> falsePositives = this.scans.falsePositives(FALSE_POSITIVES);
		Set<UUID> markers = new HashSet<>();
		falsePositives.forEach(item -> {
			if (item.markedBy() != null) {
				markers.add(item.markedBy());
			}
		});
		Map<UUID, String> names = this.accounts.names(markers);
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
					.toList(),
				falsePositives.stream()
					.map(item -> new FalsePositiveView(item.findingId(), item.productId(), item.productName(), item.ruleType(),
							item.filePath(), item.reason(), item.markedBy() == null ? null : names.get(item.markedBy()),
							item.markedAt()))
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

	/** @throws ApiException {@code NOT_FOUND}; {@code CONFLICT NOT_A_FALSE_POSITIVE} */
	@PostMapping("/api/v1/admin/failures/findings/{findingId}/reopen")
	FailuresView reopen(@PathVariable UUID findingId) {
		this.scans.reopen(findingId, this.callers.get().requireAccountId());
		return failures();
	}

}
