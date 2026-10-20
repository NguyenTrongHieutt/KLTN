package vn.vibefix.admin.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.identity.AccountAdministration.Ground;
import vn.vibefix.identity.AccountAdministration.Query;
import vn.vibefix.identity.AccountAdministration.Status;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.reputation.PrivateFeedback;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * ADM-2 (FR-5, FR-59, FR-74, FR-78, AD-20, SG-10): the users, one account with its history and, for a
 * Mentor, the private feedback (opening it is audited), and the lock, the unlock, and both revocations.
 * A revocation on the private-feedback ground needs FR-78's threshold and gives the Mentor only the
 * policy category; a 5th step-up failure ends the Administrator's session.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Access(Gate.ADMINISTRATOR)
class AdminUsersController {

	/** FR-78's policy categories: the only reasons a Mentor reads for the private-feedback ground. */
	enum Category {

		RESPECT("Thiếu tôn trọng với sinh viên"), GUIDANCE("Không hướng dẫn hoặc không phản hồi nhóm"),
		RULES("Vi phạm quy tắc của vibefix");

		private final String text;

		Category(String text) {
			this.text = text;
		}

	}

	/** The low private feedback filter (FR-78), on top of the identity statuses. */
	enum Filter {

		LOCKED, VERIFIED, PENDING, APPROVED, REJECTED, REVOKED, LOW_FEEDBACK

	}

	record UserRowView(UUID id, Role role, @Nullable String name, @Nullable String githubLogin, boolean locked,
			@Nullable String status, Instant createdAt) {
	}

	record UsersView(List<UserRowView> items, long total, int page, int size) {
	}

	record ActionView(String action, String reason, @Nullable Ground ground, @Nullable String actedBy, Instant actedAt) {
	}

	record UserVerificationView(String method, Instant verifiedAt, LocalDate validUntil, boolean current) {
	}

	/** @param comments the comments as written, for Administrators only */
	record FeedbackView(BigDecimal average, int count, List<String> comments, boolean enoughToAct) {
	}

	/** @param feedback a Mentor's private feedback, when anyone gave any (FR-78) */
	record UserDetailView(UUID id, Role role, @Nullable String name, @Nullable String githubLogin, @Nullable String email,
			Instant createdAt, @Nullable Instant signedInAt, @Nullable Instant lockedAt, @Nullable String lockReason,
			@Nullable MentorApprovalStatus approval, @Nullable UserVerificationView verification, List<ActionView> history,
			@Nullable FeedbackView feedback) {
	}

	record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
	}

	/** {@code OTHER} takes a reason; {@code PRIVATE_FEEDBACK} takes a category. */
	record RevokeApprovalRequest(@NotNull Ground ground, @Nullable @Size(max = 500) String reason,
			@Nullable Category category, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	private final AccountAdministration accounts;

	private final PrivateFeedback feedback;

	private final AuditLog auditLog;

	private final CurrentCaller callers;

	AdminUsersController(AccountAdministration accounts, PrivateFeedback feedback, AuditLog auditLog,
			CurrentCaller callers) {
		this.accounts = accounts;
		this.feedback = feedback;
		this.auditLog = auditLog;
		this.callers = callers;
	}

	@GetMapping
	UsersView users(@RequestParam(required = false) @Nullable String q, @RequestParam(required = false) @Nullable Role role,
			@RequestParam(required = false) @Nullable Filter status, @RequestParam(defaultValue = "0") int page) {
		if (role == Role.ADMINISTRATOR) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Set<UUID> ids = status == Filter.LOW_FEEDBACK ? this.feedback.belowThreshold()
			.stream()
			.map(PrivateFeedback.Summary::mentorId)
			.collect(Collectors.toSet()) : null;
		Status identityStatus = status == null || status == Filter.LOW_FEEDBACK ? null : Status.valueOf(status.name());
		AccountAdministration.Page found = this.accounts.search(new Query(q, role, identityStatus, ids, page));
		return new UsersView(found.items()
			.stream()
			.map(row -> new UserRowView(row.id(), row.role(), row.name(), row.githubLogin(), row.locked(), row.status(),
					row.createdAt()))
			.toList(), found.total(), found.page(), found.size());
	}

	@GetMapping("/{accountId}")
	@Transactional
	UserDetailView user(@PathVariable UUID accountId) {
		return detail(accountId, true);
	}

	@PostMapping("/{accountId}/lock")
	UserDetailView lock(@PathVariable UUID accountId, @Valid @RequestBody ReasonRequest body) {
		this.accounts.lock(administrator(), accountId, body.reason());
		return detail(accountId, false);
	}

	@PostMapping("/{accountId}/unlock")
	UserDetailView unlock(@PathVariable UUID accountId, @Valid @RequestBody ReasonRequest body) {
		this.accounts.unlock(administrator(), accountId, body.reason());
		return detail(accountId, false);
	}

	@PostMapping("/{accountId}/revoke-verification")
	UserDetailView revokeVerification(@PathVariable UUID accountId, @Valid @RequestBody ReasonRequest body) {
		this.accounts.revokeVerification(administrator(), accountId, body.reason());
		return detail(accountId, false);
	}

	/**
	 * "Thu hồi" (FR-74, FR-78).
	 * @throws ApiException {@code VALIDATION} without the ground's reason or category;
	 * {@code GUARD_FAILED FEEDBACK_NOT_ENOUGH}; the step-up refusal; {@code CONFLICT NOT_APPROVED}
	 */
	@PostMapping("/{accountId}/revoke-approval")
	UserDetailView revokeApproval(@PathVariable UUID accountId, @Valid @RequestBody RevokeApprovalRequest body,
			HttpServletRequest request) {
		String reason;
		if (body.ground() == Ground.PRIVATE_FEEDBACK) {
			if (body.category() == null) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			if (!this.feedback.enoughToAct(accountId)) {
				throw new ApiException(ErrorCode.GUARD_FAILED, AdminReason.FEEDBACK_NOT_ENOUGH);
			}
			reason = body.category().text;
		}
		else {
			if (body.reason() == null || body.reason().isBlank()) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			reason = body.reason();
		}
		AccountAdministration.Result result = this.accounts.revokeApproval(administrator(), accountId, body.ground(), reason,
				body.stepUpCode());
		if (!result.done()) {
			if (result.endSession()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			throw result.refusal();
		}
		return detail(accountId, false);
	}

	private UserDetailView detail(UUID accountId, boolean viewed) {
		AccountAdministration.Account account = this.accounts.account(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		FeedbackView feedback = account.role() != Role.MENTOR ? null
				: this.feedback.summary(accountId)
					.map(summary -> new FeedbackView(summary.average(), summary.count(), summary.comments(),
							this.feedback.enoughToAct(accountId)))
					.orElse(null);
		if (viewed && feedback != null) {
			// Reading a Mentor's private feedback is itself audited (FR-78, AD-8).
			this.auditLog.record(AuditEntry.of(AuditActor.user(administrator()), "admin.private_feedback_viewed",
					AuditObject.of("identity.account", accountId)));
		}
		return new UserDetailView(account.id(), account.role(), account.name(), account.githubLogin(), account.email(),
				account.createdAt(), account.signedInAt(), account.lockedAt(), account.lockReason(), account.approval(),
				account.verification() == null ? null
						: new UserVerificationView(account.verification().method(), account.verification().verifiedAt(),
								account.verification().validUntil(), account.verification().current()),
				account.history()
					.stream()
					.map(action -> new ActionView(action.action(), action.reason(), action.ground(), action.actedBy(),
							action.actedAt()))
					.toList(),
				feedback);
	}

	private UUID administrator() {
		return this.callers.get().requireAccountId();
	}

}
