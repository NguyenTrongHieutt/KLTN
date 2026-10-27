package vn.vibefix.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

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

import io.swagger.v3.oas.annotations.media.Schema;
import vn.vibefix.identity.Capabilities;
import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;
import vn.vibefix.identity.internal.SignInSession.PendingWelcome;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Kind;
import vn.vibefix.shared.Role;

/**
 * The session as the SPA sees it (AD-19): who is here, a Google sign-in waiting on PUB-3 "Chào mừng"
 * for the personal-data notice N-10, a refused locked sign-in on PUB-2, or a signed-in account.
 * Public, because each state is about the session itself and shows nothing of anyone else.
 */
@RestController
@RequestMapping("/api/v1/session")
@Access(Gate.PUBLIC)
class SessionController {

	/** Which of the four session states this is; {@code SIGNING_UP} is the welcome on PUB-3. */
	enum SessionStatus {

		ANONYMOUS, SIGNING_UP, LOCKED, SIGNED_IN

	}

	/**
	 * @param account the signed-in account; present exactly for {@code SIGNED_IN}
	 * @param githubLogin the GitHub username, for {@code SIGNED_IN} once GitHub is linked
	 * @param githubAvatarUrl the GitHub avatar, for {@code SIGNED_IN} once GitHub is linked
	 * @param displayName whom PUB-3 greets, for {@code SIGNING_UP}; the account's Google name, else its
	 * GitHub username, for {@code SIGNED_IN}
	 * @param lockReason why sign-in was refused, for {@code LOCKED}
	 * @param supportEmail whom a locked account may write to, for {@code LOCKED}
	 * @param privacyNoticeVersion the current version of the personal-data notice (N-10)
	 * @param administrator an Administrator's name and session limits, exactly for Administrators
	 */
	record SessionView(SessionStatus status, @Nullable AccountView account, @Nullable String githubLogin,
			@Nullable String githubAvatarUrl, @Nullable String displayName, @Nullable String lockReason,
			@Nullable String supportEmail, String privacyNoticeVersion, @Nullable AdministratorView administrator) {
	}

	/**
	 * An Administrator session (SG-10).
	 * @param idleExpiresAt when it ends without a command or "Tiếp tục phiên"
	 * @param absoluteExpiresAt when it ends at the latest, 8 hours after sign-in
	 */
	record AdministratorView(String email, String displayName, Instant idleExpiresAt, Instant absoluteExpiresAt) {
	}

	/**
	 * The signed-in account (AD-19): its kind and its capabilities, read from the database on this request.
	 * @param kind {@code USER} or {@code ADMINISTRATOR}; the SPA tells an Administrator by it
	 * @param capabilities what the account can show for itself now, which the SPA's gates read (UX-DR95)
	 * @param theme the account's Theme toggle choice (UX-DR5)
	 * @param role deprecated and transitional: the account role of a row from before Google sign-in and of an
	 * Administrator; absent for a Google account, which holds roles in Jobs only. Story 20.6 removes it with
	 * the shell that still branches on it; nothing new may read it.
	 */
	record AccountView(UUID id, Kind kind, CapabilitiesView capabilities, Profiles.Theme theme,
			@Schema(deprecated = true) @Nullable Role role) {
	}

	/**
	 * {@code identity.capabilities} as the SPA reads it.
	 * @param linkedinVerified always {@code false} until Story 19.6
	 * @param locked never {@code true} here: a locked account has no session (the caller filter ends it), so
	 * {@code GET /session} and the sign-up answer only for an unlocked one
	 */
	record CapabilitiesView(boolean githubVerified, boolean linkedinVerified, boolean studentVerified,
			boolean locked) {

		static CapabilitiesView of(Capabilities.Held held) {
			return new CapabilitiesView(held.githubVerified(), held.linkedinVerified(), held.studentVerified(),
					held.locked());
		}

	}

	/** @param privacyNoticeVersion the version of the personal-data notice the person accepted */
	record SignUpRequest(@NotBlank String privacyNoticeVersion) {
	}

	private final CurrentCaller callers;

	private final Accounts accounts;

	private final SignIns signIns;

	private final SecurityContextRepository contexts;

	private final IdentityProperties properties;

	private final Profiles profiles;

	private final AdministratorCredentials administrators;

	private final Capabilities capabilities;

	private final Clock clock;

	SessionController(CurrentCaller callers, Accounts accounts, SignIns signIns, SecurityContextRepository contexts,
			IdentityProperties properties, Profiles profiles, AdministratorCredentials administrators,
			Capabilities capabilities, Clock clock) {
		this.administrators = administrators;
		this.capabilities = capabilities;
		this.clock = clock;
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
			return signedIn(this.accounts.findById(caller.requireAccountId()).orElseThrow(), request);
		}
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(SignInSession.LOCKED) instanceof SignInSession.Locked locked) {
			return new SessionView(SessionStatus.LOCKED, null, null, null, null, locked.reason(),
					this.properties.supportEmail(), version(), null);
		}
		if (session != null && session.getAttribute(SignInSession.PENDING_WELCOME) instanceof PendingWelcome pending) {
			return new SessionView(SessionStatus.SIGNING_UP, null, null, null, pending.displayName(), null, null,
					version(), null);
		}
		return new SessionView(SessionStatus.ANONYMOUS, null, null, null, null, null, null, version(), null);
	}

	/**
	 * PUB-3's "Đồng ý và bắt đầu": records the acknowledgment of the personal-data notice with its
	 * version, opens the {@code identity.link_github} to-do, then signs the account in on a new
	 * session ID. A notice changed since the screen loaded answers {@code 409 STALE}; an account
	 * locked meanwhile, {@code 404 NOT_FOUND}.
	 */
	@PostMapping("/sign-up")
	ResponseEntity<SessionView> signUp(@Valid @RequestBody SignUpRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		if (!this.callers.get().isAnonymous()) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(SignInSession.PENDING_WELCOME) instanceof PendingWelcome pending)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		if (!version().equals(body.privacyNoticeVersion())) {
			throw new ApiException(ErrorCode.STALE);
		}
		Account account;
		try {
			account = this.signIns.welcome(pending.accountId(), body.privacyNoticeVersion());
		}
		catch (ApiException ex) {
			SignInSession.clear(session);
			throw ex;
		}
		SignInSession.clear(session);
		request.changeSessionId();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new AccountAuthentication(account.id(), account.kind()));
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		return ResponseEntity.status(HttpStatus.CREATED).body(signedIn(account, request));
	}

	/**
	 * "Tiếp tục phiên", and the SPA's throttled ping after real input: a command, so it extends an
	 * Administrator session (the caller filter records it); for anyone else it changes nothing.
	 */
	@PostMapping("/activity")
	@Access(Gate.SIGNED_IN)
	SessionView activity(HttpServletRequest request) {
		return signedIn(this.accounts.findById(this.callers.get().requireAccountId()).orElseThrow(), request);
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

	/** The signed-in session, with the capabilities read now (AD-19). */
	private SessionView signedIn(Account account, HttpServletRequest request) {
		CapabilitiesView capabilities = CapabilitiesView.of(this.capabilities.capabilities(account.id()));
		return new SessionView(SessionStatus.SIGNED_IN,
				new AccountView(account.id(), account.kind(), capabilities, this.profiles.theme(account.id()),
						account.role()),
				account.githubLogin(), account.githubAvatarUrl(), account.displayName(), null, null, version(),
				administrator(account, request));
	}

	private @Nullable AdministratorView administrator(Account account, HttpServletRequest request) {
		if (account.kind() != Kind.ADMINISTRATOR) {
			return null;
		}
		AdminSessions.Expiry expiry = AdminSessions.current(request.getSession(false), this.clock.instant());
		return this.administrators.identity(account.id())
			.filter(found -> expiry != null)
			.map(found -> new AdministratorView(found.email(), found.displayName(), expiry.idle(), expiry.absolute()))
			.orElse(null);
	}

	private String version() {
		return this.properties.privacyNoticeVersion();
	}

}
