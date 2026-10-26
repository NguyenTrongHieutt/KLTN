package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.SignInSession.PendingWelcome;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.shared.Role;

/**
 * The account side of signing in (AD-19, FR-1). One account per person, keyed by Google's subject,
 * with no role: the first Google sign-in creates it and the welcome (PUB-3) records the personal-data
 * notice N-10. GitHub signs nobody in: a signed-in account links it ({@link GitHubLinksImpl}).
 */
@Component
class SignIns {

	static final String PRIVACY_NOTICE = "PRIVACY";

	static final String COMPLETE_MENTOR_APPLICATION = "identity.complete_mentor_application";

	/** ACC-2, the verification hub, where the student verification lives since Story 19.3. */
	static final String VERIFICATION_PATH = "/ho-so/xac-minh";

	/** MEN-5, the Mentor application, beside ACC-1 since Story 19.3 (until Story 19.7). */
	static final String MENTOR_PROFILE_PATH = "/ho-so/nguoi-huong-dan";

	static final String VERIFY_STUDENT = "identity.verify_student";

	/** Opened by the welcome and open until GitHub is linked (UX-DR89, UX-DR112); it leads to ACC-2. */
	static final String LINK_GITHUB = "identity.link_github";

	static final String LINK_GITHUB_PATH = "/ho-so/xac-minh";

	/** What a sign-in led to. */
	sealed interface Outcome {

		record SignedIn(Account account) implements Outcome {
		}

		record Locked(String reason) implements Outcome {
		}

		/** The account exists, and waits on PUB-3 for the current N-10. */
		record NeedsWelcome(PendingWelcome pending) implements Outcome {
		}

	}

	private final Accounts accounts;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final Todos todos;

	private final JdbcPersonSignals signals;

	private final IdentityProperties properties;

	SignIns(Accounts accounts, AuditLog auditLog, IdGenerator ids, Clock clock, Todos todos, JdbcPersonSignals signals,
			IdentityProperties properties) {
		this.properties = properties;
		this.signals = signals;
		this.accounts = accounts;
		this.todos = todos;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * Completes a Google sign-in for the subject Google returned, whose email Google verified (FR-1).
	 * The first one creates the account, with no role; every one reads the email and the name again,
	 * and a new address records its own person-link signal (AD-19). An Administrator is never found
	 * or created here (FR-2).
	 */
	@Transactional
	Outcome signInWithGoogle(String googleSub, String googleEmail, @Nullable String googleName) {
		Instant now = this.clock.instant();
		String name = googleName == null || googleName.isBlank() ? null : googleName.strip();
		UUID id = this.ids.newId();
		if (this.accounts.insertGoogleUser(id, googleSub, googleEmail, name, now)) {
			this.signals.record(id, JdbcPersonSignals.Source.GOOGLE_EMAIL, googleEmail);
			this.auditLog.record(AuditEntry.of(AuditActor.user(id), "identity.account_created",
					AuditObject.of("identity.account", id)));
			return new Outcome.NeedsWelcome(new PendingWelcome(id, displayName(name, googleEmail)));
		}
		Account account = this.accounts.findByGoogleSub(googleSub).orElseThrow();
		if (account.isLocked()) {
			return new Outcome.Locked(account.lockReason() == null ? "" : account.lockReason());
		}
		if (!googleEmail.equals(account.googleEmail())) {
			this.signals.record(account.id(), JdbcPersonSignals.Source.GOOGLE_EMAIL, googleEmail);
		}
		this.accounts.recordGoogleSignIn(account.id(), googleEmail, name, now);
		if (!this.accounts.hasAcknowledged(account.id(), PRIVACY_NOTICE, this.properties.privacyNoticeVersion())) {
			return new Outcome.NeedsWelcome(new PendingWelcome(account.id(), displayName(name, googleEmail)));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.user(account.id()), "identity.signed_in",
				AuditObject.of("identity.account", account.id())));
		return new Outcome.SignedIn(this.accounts.findById(account.id()).orElseThrow());
	}

	/**
	 * PUB-3's "Đồng ý và bắt đầu": records the acknowledgment of N-10 with its version (NFR-9), opens
	 * {@code identity.link_github} while GitHub is not linked, and audits the sign-in it completes.
	 * @throws ApiException {@code NOT_FOUND} when the account is locked, erased, or gone meanwhile
	 */
	@Transactional
	Account welcome(UUID accountId, String privacyNoticeVersion) {
		Account account = this.accounts.findById(accountId)
			.filter(Account::isActive)
			.filter(found -> found.kind() == Account.Kind.USER)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		UUID acknowledgment = this.ids.newId();
		if (this.accounts.insertAcknowledgment(acknowledgment, accountId, PRIVACY_NOTICE, privacyNoticeVersion,
				this.clock.instant())) {
			this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.notice_acknowledged",
					AuditObject.of("identity.account", accountId), AuditObject.of("identity.acknowledgment", acknowledgment)));
		}
		if (account.githubUserId() == null) {
			this.todos.open(new TodoRequest(LINK_GITHUB, accountId, Audience.SUBJECT, accountId, Map.of(), LINK_GITHUB_PATH,
					null, null));
		}
		// The sign-in completes here, so it is audited here as on the direct path.
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), "identity.signed_in",
				AuditObject.of("identity.account", accountId)));
		return account;
	}

	/**
	 * Where an account lands after signing in: a Mentor from before Google sign-in waiting for approval
	 * sees only MEN-5 (FR-74); everyone else, a Google account with no role too, the to-do list.
	 */
	static String landingPath(Account account) {
		return account.role() == Role.MENTOR && account.mentorApprovalStatus() != MentorApprovalStatus.APPROVED
				? MENTOR_PROFILE_PATH : "/viec-can-lam";
	}

	/** PUB-3 greets the Google name, else the address. */
	private static String displayName(@Nullable String name, String email) {
		return name != null ? name : email;
	}

}
