package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.SignInSession.PendingSignUp;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * The account side of signing in and signing up (AD-19, FR-1). One account per GitHub numeric ID,
 * one permanent role; only a Customer's token is kept, sealed with the account ID (AD-22, SG-9).
 */
@Component
class SignIns {

	static final String PRIVACY_NOTICE = "PRIVACY";

	static final String COMPLETE_MENTOR_APPLICATION = "identity.complete_mentor_application";

	static final String VERIFY_STUDENT = "identity.verify_student";

	/** What a GitHub sign-in led to. */
	sealed interface Outcome {

		record SignedIn(Account account) implements Outcome {
		}

		record Locked(String reason) implements Outcome {
		}

		record NeedsRole(PendingSignUp pending) implements Outcome {
		}

	}

	private final Accounts accounts;

	private final TokenCipher cipher;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final Todos todos;

	SignIns(Accounts accounts, TokenCipher cipher, AuditLog auditLog, IdGenerator ids, Clock clock, Todos todos) {
		this.accounts = accounts;
		this.todos = todos;
		this.cipher = cipher;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Completes a GitHub sign-in for the user GitHub returned.
	 * @param githubEmail the primary verified email GitHub gave, or {@code null} when it gave none or
	 * did not answer: the address on file then stays
	 */
	@Transactional
	Outcome signIn(long githubUserId, String githubLogin, @Nullable String githubAvatarUrl, @Nullable String githubEmail,
			@Nullable String token) {
		Optional<Account> existing = this.accounts.findByGitHubUserId(githubUserId);
		if (existing.isEmpty()) {
			TokenCipher.Sealed sealed = token == null ? null
					: this.cipher.seal(token, PendingSignUp.tokenAssociatedData(githubUserId));
			return new Outcome.NeedsRole(
					new PendingSignUp(githubUserId, githubLogin, githubAvatarUrl, githubEmail, sealed));
		}
		Account account = existing.get();
		if (account.isLocked()) {
			return new Outcome.Locked(account.lockReason() == null ? "" : account.lockReason());
		}
		Instant now = this.clock.instant();
		this.accounts.recordSignIn(account.id(), githubLogin, githubAvatarUrl, now);
		if (githubEmail != null) {
			this.accounts.recordGitHubEmail(account.id(), githubEmail);
		}
		if (account.role() == Role.CUSTOMER && token != null) {
			this.accounts.storeCustomerToken(account.id(), this.cipher.seal(token, account.id().toString()), now);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(account.id()), "identity.signed_in",
				AuditObject.of("identity.account", account.id())));
		return new Outcome.SignedIn(this.accounts.findById(account.id()).orElseThrow());
	}

	/**
	 * Creates the account of a pending sign-up with its permanent role, records the acknowledgment
	 * of the personal-data notice with its version, and keeps the token for a Customer only.
	 * @throws ApiException {@code CONFLICT} when the GitHub account already has an account
	 */
	@Transactional
	Account signUp(PendingSignUp pending, Role role, String privacyNoticeVersion) {
		if (role == Role.ADMINISTRATOR) {
			throw new IllegalArgumentException("Administrators are created by Administrators (FR-2)");
		}
		Instant now = this.clock.instant();
		Account account = new Account(this.ids.newId(), pending.githubUserId(), pending.githubLogin(),
				pending.githubAvatarUrl(), role, role == Role.MENTOR ? MentorApprovalStatus.PENDING : null, null, null,
				null, now);
		try {
			this.accounts.insert(account);
		}
		catch (DuplicateKeyException ex) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		if (pending.githubEmail() != null) {
			this.accounts.recordGitHubEmail(account.id(), pending.githubEmail());
		}
		openSignUpTodos(account);
		UUID acknowledgment = this.ids.newId();
		this.accounts.insertAcknowledgment(acknowledgment, account.id(), PRIVACY_NOTICE, privacyNoticeVersion, now);
		if (role == Role.CUSTOMER && pending.token() != null) {
			String token = this.cipher.open(pending.token(), PendingSignUp.tokenAssociatedData(pending.githubUserId()));
			this.accounts.storeCustomerToken(account.id(), this.cipher.seal(token, account.id().toString()), now);
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(account.id()), "identity.account_created",
				AuditObject.of("identity.account", account.id())));
		this.auditLog.record(AuditEntry.of(AuditActor.user(account.id()), "identity.notice_acknowledged",
				AuditObject.of("identity.account", account.id()), AuditObject.of("identity.acknowledgment", acknowledgment)));
		return account;
	}

	/**
	 * The to-dos a new account starts with (EXPERIENCE.md, To-do Items): a Mentor completes the
	 * application (FR-74), a Student verifies their student status (FR-5); both on {@code /ho-so}.
	 * Their own stories close them.
	 */
	private void openSignUpTodos(Account account) {
		String key = switch (account.role()) {
			case MENTOR -> COMPLETE_MENTOR_APPLICATION;
			case STUDENT -> VERIFY_STUDENT;
			default -> null;
		};
		if (key != null) {
			this.todos.open(new TodoRequest(key, account.id(), Audience.SUBJECT, account.id(), Map.of(), "/ho-so", null,
					null));
		}
	}

	/** Where an account lands after signing in: a Mentor waiting for approval sees only MEN-5 (FR-74). */
	static String landingPath(Account account) {
		return account.role() == Role.MENTOR && account.mentorApprovalStatus() != MentorApprovalStatus.APPROVED
				? "/ho-so" : "/viec-can-lam";
	}

}
