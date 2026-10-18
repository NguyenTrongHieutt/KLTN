package vn.vibefix.identity.internal;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;
import vn.vibefix.identity.internal.SignInSession.PendingSignUp;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * The session as the SPA sees it (AD-19): who is here, a pending first sign-in choosing its role on
 * PUB-3, a refused locked sign-in on PUB-2, or a signed-in account. Public, because each state is
 * about the session itself and shows nothing of anyone else.
 */
@RestController
@RequestMapping("/api/v1/session")
@Access(Gate.PUBLIC)
class SessionController {

	/** Which of the four session states this is. */
	enum SessionStatus {

		ANONYMOUS, SIGNING_UP, LOCKED, SIGNED_IN

	}

	/** The roles a person chooses at sign-up; Administrators are created by Administrators (FR-2). */
	enum SignUpRole {

		CUSTOMER, MENTOR, STUDENT

	}

	/**
	 * @param account the signed-in account; present exactly for {@code SIGNED_IN}
	 * @param githubLogin the GitHub username, for {@code SIGNING_UP} and {@code SIGNED_IN}
	 * @param githubAvatarUrl the GitHub avatar, for {@code SIGNING_UP} and {@code SIGNED_IN}
	 * @param lockReason why sign-in was refused, for {@code LOCKED}
	 * @param supportEmail whom a locked account may write to, for {@code LOCKED}
	 * @param privacyNoticeVersion the current version of the personal-data notice (N-10)
	 */
	record SessionView(SessionStatus status, @Nullable AccountView account, @Nullable String githubLogin,
			@Nullable String githubAvatarUrl, @Nullable String lockReason, @Nullable String supportEmail,
			String privacyNoticeVersion) {
	}

	/**
	 * @param mentorApprovalStatus set exactly for Mentors
	 * @param theme the account's Theme toggle choice (UX-DR5)
	 */
	record AccountView(UUID id, Role role, @Nullable MentorApprovalStatus mentorApprovalStatus, Profiles.Theme theme) {
	}

	/**
	 * @param role the permanent role
	 * @param privacyNoticeVersion the version of the personal-data notice the person accepted
	 */
	record SignUpRequest(@NotNull SignUpRole role, @NotBlank String privacyNoticeVersion) {
	}

	private final CurrentCaller callers;

	private final Accounts accounts;

	private final SignIns signIns;

	private final SecurityContextRepository contexts;

	private final IdentityProperties properties;

	private final Profiles profiles;

	SessionController(CurrentCaller callers, Accounts accounts, SignIns signIns, SecurityContextRepository contexts,
			IdentityProperties properties, Profiles profiles) {
		this.profiles = profiles;
		this.callers = callers;
		this.accounts = accounts;
		this.signIns = signIns;
		this.contexts = contexts;
		this.properties = properties;
	}

	@GetMapping
	SessionView session(HttpServletRequest request) {
		Caller caller = this.callers.get();
		if (!caller.isAnonymous()) {
			return signedIn(this.accounts.findById(caller.requireAccountId()).orElseThrow());
		}
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(SignInSession.LOCKED) instanceof SignInSession.Locked locked) {
			return new SessionView(SessionStatus.LOCKED, null, null, null, locked.reason(),
					this.properties.supportEmail(), version());
		}
		if (session != null && session.getAttribute(SignInSession.PENDING_SIGN_UP) instanceof PendingSignUp pending) {
			return new SessionView(SessionStatus.SIGNING_UP, null, pending.githubLogin(), pending.githubAvatarUrl(), null,
					null, version());
		}
		return new SessionView(SessionStatus.ANONYMOUS, null, null, null, null, null, version());
	}

	/**
	 * PUB-3's choice: creates the account with its permanent role and the acknowledgment of the
	 * personal-data notice, then signs it in. A notice changed since the screen loaded answers
	 * {@code 409 STALE}.
	 */
	@PostMapping("/sign-up")
	ResponseEntity<SessionView> signUp(@Valid @RequestBody SignUpRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		if (!this.callers.get().isAnonymous()) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(SignInSession.PENDING_SIGN_UP) instanceof PendingSignUp pending)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!version().equals(body.privacyNoticeVersion())) {
			throw new ApiException(ErrorCode.STALE);
		}
		Account account = this.signIns.signUp(pending, Role.valueOf(body.role().name()), body.privacyNoticeVersion());
		SignInSession.clear(session);
		request.changeSessionId();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new AccountAuthentication(account.id(), account.role()));
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		return ResponseEntity.status(HttpStatus.CREATED).body(signedIn(account));
	}

	/** "Đăng xuất": ends the server session, signed in or not. */
	@PostMapping("/sign-out")
	ResponseEntity<Void> signOut(HttpServletRequest request) {
		SecurityContextHolder.clearContext();
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.noContent().build();
	}

	private SessionView signedIn(Account account) {
		return new SessionView(SessionStatus.SIGNED_IN,
				new AccountView(account.id(), account.role(), account.mentorApprovalStatus(),
						this.profiles.theme(account.id())),
				account.githubLogin(),
				account.githubAvatarUrl(), null, null, version());
	}

	private String version() {
		return this.properties.privacyNoticeVersion();
	}

}
