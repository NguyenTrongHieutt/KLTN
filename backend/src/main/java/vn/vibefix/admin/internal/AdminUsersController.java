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
import vn.vibefix.identity.AccountAdministration.Query;
import vn.vibefix.identity.AccountAdministration.SignalKind;
import vn.vibefix.identity.AccountAdministration.Status;
import vn.vibefix.reputation.PrivateFeedback;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * ADM-2 (FR-5, FR-59, FR-78, AD-19, AD-20, SG-10): the users, one account with its history, the accounts
 * that may be the same person ("Tài khoản có thể cùng một người") and the private feedback about them as a Mentor
 * (opening either is audited), and the lock, the unlock, and the erasure on the person's request. Nobody is
 * approved or revoked here (Story 19.7), and no account carries a role (Story 20.3); a 5th step-up failure ends
 * the Administrator's session.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Access(Gate.ADMINISTRATOR)
class AdminUsersController {

	/** The low private feedback filter (FR-78), on top of the identity statuses. */
	enum Filter {

		LOCKED, VERIFIED, LOW_FEEDBACK

	}

	/** @param status {@code VERIFIED} for a verified student; absent otherwise */
	record UserRowView(UUID id, @Nullable String name, @Nullable String githubLogin, boolean locked,
			@Nullable String status, Instant createdAt) {
	}

	record UsersView(List<UserRowView> items, long total, int page, int size) {
	}

	/**
	 * @param action {@code LOCKED} or {@code UNLOCKED}; {@code VERIFICATION_REVOKED} and {@code APPROVAL_REVOKED}
	 * stay readable on the history from before Story 19.7
	 * @param ground an old approval revocation's ground, {@code OTHER} or {@code PRIVATE_FEEDBACK}
	 */
	record ActionView(String action, String reason, @Nullable String ground, @Nullable String actedBy, Instant actedAt) {
	}

	/**
	 * Another account that shares a person-link signal with this one (AD-19), never the signal.
	 * @param name absent for an erased account
	 * @param kinds the kinds they share, each once
	 * @param createdAt when that account was created
	 */
	record PersonLinkView(UUID accountId, @Nullable String name, List<SignalKind> kinds, boolean locked, boolean erased,
			Instant createdAt) {
	}

	record UserVerificationView(String method, Instant verifiedAt, LocalDate validUntil, boolean current) {
	}

	/** @param comments the comments as written, for Administrators only */
	record FeedbackView(BigDecimal average, int count, List<String> comments) {
	}

	/**
	 * @param jobId the Job a blocker concerns, when there is one
	 * @param name that Job's title, when there is one
	 */
	record DeletionBlockerView(String module, String reason, @Nullable UUID jobId, @Nullable String name) {
	}

	/** NFR-11 (Story 16.2): the person's deletion request and what keeps it waiting. */
	record DeletionRequestView(Instant requestedAt, @Nullable Instant completedAt, List<DeletionBlockerView> blockers) {
	}

	/** The account after "Xác nhận xóa tài khoản": erased, kept only as NFR-10 requires. */
	record AccountErasedView(UUID id, boolean erased) {
	}

	/**
	 * @param personLinks the accounts that may be the same person, the oldest first (AD-19)
	 * @param feedback the private feedback about the person as a Mentor, when anyone gave any (FR-78); ADM-2 shows
	 * its section for every user, with its Empty state while there is none
	 * @param deletionRequest the person's account deletion request, if they made one (NFR-11)
	 */
	record UserDetailView(UUID id, @Nullable String name, @Nullable String githubLogin, @Nullable String email,
			Instant createdAt, @Nullable Instant signedInAt, @Nullable Instant lockedAt, @Nullable String lockReason,
			@Nullable UserVerificationView verification, List<ActionView> history, List<PersonLinkView> personLinks,
			@Nullable FeedbackView feedback, @Nullable DeletionRequestView deletionRequest) {
	}

	record EraseRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String stepUpCode) {
	}

	record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
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
	UsersView users(@RequestParam(required = false) @Nullable String q,
			@RequestParam(required = false) @Nullable Filter status, @RequestParam(defaultValue = "0") int page) {
		Set<UUID> ids = status == Filter.LOW_FEEDBACK ? this.feedback.belowThreshold()
			.stream()
			.map(PrivateFeedback.Summary::mentorId)
			.collect(Collectors.toSet()) : null;
		Status identityStatus = status == null || status == Filter.LOW_FEEDBACK ? null : Status.valueOf(status.name());
		AccountAdministration.Page found = this.accounts.search(new Query(q, identityStatus, ids, page));
		return new UsersView(found.items()
			.stream()
			.map(row -> new UserRowView(row.id(), row.name(), row.githubLogin(), row.locked(), row.status(),
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

	/**
	 * "Xác nhận xóa tài khoản" (NFR-11, AD-24): behind the step-up, only on the person's request and with no
	 * blocker left.
	 * @throws ApiException the step-up refusal; {@code GUARD_FAILED NO_DELETION_REQUEST}; {@code GUARD_FAILED
	 * ERASURE_BLOCKED} with the blockers; {@code FORBIDDEN ADMINISTRATOR_ACCOUNT}
	 */
	@PostMapping("/{accountId}/erase")
	AccountErasedView erase(@PathVariable UUID accountId, @Valid @RequestBody EraseRequest body, HttpServletRequest request) {
		AccountAdministration.Result result = this.accounts.erase(administrator(), accountId, body.stepUpCode());
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
		return new AccountErasedView(accountId, true);
	}

	private UserDetailView detail(UUID accountId, boolean viewed) {
		AccountAdministration.Account account = this.accounts.account(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// Anyone may be a Mentor in some Job (Story 20.3): the private feedback is read for every user.
		FeedbackView feedback = this.feedback.summary(accountId)
			.map(summary -> new FeedbackView(summary.average(), summary.count(), summary.comments()))
			.orElse(null);
		if (viewed && feedback != null) {
			// Reading a Mentor's private feedback is itself audited (FR-78, AD-8).
			this.auditLog.record(AuditEntry.of(AuditActor.user(administrator()), "admin.private_feedback_viewed",
					AuditObject.of("identity.account", accountId)));
		}
		// Opening ADM-2 reads the person links under audit; a lock's or an unlock's answer does not (AD-8).
		List<PersonLinkView> personLinks = (viewed ? this.accounts.viewPersonLinks(administrator(), accountId)
				: this.accounts.personLinks(accountId))
			.stream()
			.map(link -> new PersonLinkView(link.accountId(), link.name(), link.kinds(), link.locked(), link.erased(),
					link.createdAt()))
			.toList();
		return new UserDetailView(account.id(), account.name(), account.githubLogin(), account.email(),
				account.createdAt(), account.signedInAt(), account.lockedAt(), account.lockReason(),
				account.verification() == null ? null
						: new UserVerificationView(account.verification().method(), account.verification().verifiedAt(),
								account.verification().validUntil(), account.verification().current()),
				account.history()
					.stream()
					.map(action -> new ActionView(action.action(), action.reason(), action.ground(), action.actedBy(),
							action.actedAt()))
					.toList(),
				personLinks, feedback,
				this.accounts.deletionRequest(accountId)
					.map(deletion -> new DeletionRequestView(deletion.requestedAt(), deletion.completedAt(),
							deletion.blockers()
								.stream()
								.map(blocker -> new DeletionBlockerView(blocker.module(), blocker.reason(), blocker.jobId(),
										blocker.name()))
								.toList()))
					.orElse(null));
	}

	private UUID administrator() {
		return this.callers.get().requireAccountId();
	}

}
