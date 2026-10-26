package vn.vibefix.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import io.swagger.v3.oas.annotations.Hidden;
import vn.vibefix.identity.GitHubLinkBlocker;
import vn.vibefix.identity.Notices;
import vn.vibefix.identity.internal.GitHubLinkReader.Answer;
import vn.vibefix.identity.internal.GitHubLinksImpl.Outcome;
import vn.vibefix.identity.internal.GitHubLinksImpl.Purpose;
import vn.vibefix.identity.internal.SignInSession.PendingLink;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;
import vn.vibefix.shared.Role;

/**
 * ACC-2's GitHub row and the GitHub gate (FR-80, AD-10, AD-19, UX-DR91, UX-DR95). Linking runs inside a
 * signed-in session and never creates one: the start records N-24, binds a random {@code state} and a
 * PKCE verifier to the session, and sends the browser to GitHub with exactly {@code read:user user:email};
 * the callback, outside {@code /api} and outside Spring's login filter, accepts that {@code state} once,
 * within 10 minutes, from the account that started it, then links what GitHub returned. No token, code,
 * or claim is ever logged.
 */
@RestController
class GitHubLinkController {

	/** ACC-2, where a link returns unless it was started elsewhere, and every failed link. */
	static final String ACC_2 = "/ho-so/xac-minh";

	/** How long a started link may take to come back. */
	static final Duration LINK_LIFETIME = Duration.ofMinutes(10);

	static final String SCOPE = "read:user user:email";

	private static final Logger log = LoggerFactory.getLogger(GitHubLinkController.class);

	/**
	 * @param noticeVersion the version of N-24 the person accepted
	 * @param purpose {@code PRODUCT} from CUS-3 step 2, which keeps the token; {@code PROFILE} from ACC-2
	 * @param returnTo a relative path inside the SPA to come back to; ACC-2 when absent
	 */
	record GitHubLinkRequest(@NotBlank String noticeVersion, @NotNull Purpose purpose,
			@Nullable @Size(max = 200) String returnTo) {
	}

	/** Where the SPA sends the browser: GitHub's authorization page. */
	record GitHubLinkStartView(String authorizationUrl) {
	}

	/**
	 * Something that keeps the link as it is (R14).
	 * @param reason {@code LIVE_PRODUCT}, {@code JOB_ROLE}, or {@code OPEN_BID}
	 * @param name the Product's name or the Job's title
	 */
	record GitHubLinkBlockerView(String module, String reason, UUID subjectId, String name) {

		static GitHubLinkBlockerView of(GitHubLinkBlocker blocker) {
			return new GitHubLinkBlockerView(blocker.module(), blocker.reason(), blocker.subjectId(), blocker.name());
		}

	}

	/**
	 * ACC-2's GitHub row.
	 * @param login the GitHub username, while linked
	 * @param linkedAt when GitHub was last linked: "Xác minh qua GitHub · {tháng/năm}"
	 * @param relinkNeeded "Cần liên kết lại": the kept token stopped working at a re-check
	 * @param repositoryTokenKept whether vibefix keeps a token to read the account's repositories; CUS-3
	 * step 2 links again without one
	 * @param blockers what disables "Bỏ liên kết", while linked
	 * @param noticeVersion N-24's current version, which the start must name
	 */
	record GitHubLinkView(boolean linked, @Nullable String login, @Nullable String avatarUrl, @Nullable Instant linkedAt,
			boolean relinkNeeded, boolean repositoryTokenKept, List<GitHubLinkBlockerView> blockers, String noticeVersion) {
	}

	private final CurrentCaller callers;

	private final Accounts accounts;

	private final GitHubLinksImpl links;

	private final GitHubLinkReader reader;

	private final GitHubSignIn signIn;

	private final IdentityProperties properties;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	GitHubLinkController(CurrentCaller callers, Accounts accounts, GitHubLinksImpl links, GitHubLinkReader reader,
			GitHubSignIn signIn, IdentityProperties properties, Clock clock) {
		this.callers = callers;
		this.accounts = accounts;
		this.links = links;
		this.reader = reader;
		this.signIn = signIn;
		this.properties = properties;
		this.clock = clock;
	}

	/** ACC-2's GitHub row: linked or not, re-link needed, and what blocks "Bỏ liên kết". */
	@GetMapping("/api/v1/identity/github")
	@Access(Gate.SIGNED_IN)
	GitHubLinkView read() {
		GitHubLinksImpl.Status status = this.links.status(this.callers.get().requireAccountId());
		return new GitHubLinkView(status.linked(), status.login(), status.avatarUrl(), status.linkedAt(),
				status.relinkNeeded(), status.repositoryTokenKept(),
				status.blockers().stream().map(GitHubLinkBlockerView::of).toList(), Notices.LINK_GITHUB_VERSION);
	}

	/**
	 * "Liên kết GitHub ↗ (github.com)" after N-24: records the {@code LINK_GITHUB} acknowledgment and
	 * answers GitHub's authorization page for this session.
	 * @throws ApiException {@code NOT_FOUND} for an Administrator; {@code FORBIDDEN PRIVACY_NOTICE_REQUIRED}
	 * before the current N-10 is accepted; {@code VALIDATION} for {@code PRODUCT} from an account that is
	 * neither a Customer nor role-less; {@code GUARD_FAILED GITHUB_NOT_SET_UP} without a linking OAuth
	 * App; {@code STALE} for an N-24 version that is not the current one; {@code VALIDATION} for a
	 * {@code returnTo} outside the SPA
	 */
	@PostMapping("/api/v1/identity/github/link")
	@Access(Gate.SIGNED_IN)
	GitHubLinkStartView start(@Valid @RequestBody GitHubLinkRequest body, HttpServletRequest request) {
		UUID accountId = this.callers.get().requireAccountId();
		Account account = this.accounts.findById(accountId)
			.filter(found -> found.kind() == Account.Kind.USER && found.isActive())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!this.accounts.hasAcknowledged(accountId, SignIns.PRIVACY_NOTICE, this.properties.privacyNoticeVersion())) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.PRIVACY_NOTICE_REQUIRED);
		}
		// Only who may register a Product links for one: a Customer, or a Google account, which holds no role.
		if (body.purpose() == Purpose.PRODUCT && account.role() != null && account.role() != Role.CUSTOMER) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		ClientRegistration registration = this.signIn.registration()
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, IdentityReason.GITHUB_NOT_SET_UP));
		if (!Notices.LINK_GITHUB_VERSION.equals(body.noticeVersion())) {
			throw new ApiException(ErrorCode.STALE);
		}
		String returnTo = null;
		if (body.returnTo() != null && !body.returnTo().isBlank()) {
			returnTo = SignInStartFilter.safeReturnPath(body.returnTo());
			if (returnTo == null) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
		}
		this.links.consent(accountId);
		String state = randomToken();
		String verifier = randomToken();
		String redirectUri = baseUrl(request) + GitHubSignIn.CALLBACK_PATH;
		request.getSession().setAttribute(SignInSession.PENDING_LINK,
				new PendingLink(accountId, state, verifier, body.purpose(), redirectUri, returnTo, this.clock.instant()));
		String url = UriComponentsBuilder.fromUriString(registration.getProviderDetails().getAuthorizationUri())
			.queryParam("response_type", "code")
			.queryParam("client_id", registration.getClientId())
			.queryParam("redirect_uri", redirectUri)
			.queryParam("scope", SCOPE)
			.queryParam("state", state)
			.queryParam("code_challenge", challenge(verifier))
			.queryParam("code_challenge_method", "S256")
			.queryParam("allow_signup", "false")
			.encode()
			.build()
			.toUriString();
		return new GitHubLinkStartView(url);
	}

	/**
	 * GitHub's return. The pending link is taken from the session first, so a {@code state} is accepted
	 * once; anything wrong (no session, another account, a wrong or old {@code state}, a refused code)
	 * writes nothing and lands on ACC-2 with {@code loi=that-bai}.
	 */
	@Hidden
	@GetMapping(GitHubSignIn.CALLBACK_PATH)
	void callback(@RequestParam(required = false) @Nullable String code, @RequestParam(required = false) @Nullable String state,
			@RequestParam(required = false) @Nullable String error, HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		String target;
		try {
			target = finish(code, state, error, request);
		}
		catch (RuntimeException failed) {
			// The class only: a message could hold the code, the token, or a claim.
			log.warn("A GitHub link failed: {}", failed.getClass().getSimpleName());
			target = failed("that-bai");
		}
		response.sendRedirect(target);
	}

	/** "Bỏ liên kết", after the Confirmation dialog. */
	@DeleteMapping("/api/v1/identity/github")
	@Access(Gate.SIGNED_IN)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unlink() {
		this.links.unlink(this.callers.get().requireAccountId());
	}

	private String finish(@Nullable String code, @Nullable String state, @Nullable String error,
			HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null || !(session.getAttribute(SignInSession.PENDING_LINK) instanceof PendingLink pending)) {
			return failed("that-bai");
		}
		session.removeAttribute(SignInSession.PENDING_LINK);
		var caller = this.callers.get();
		if (caller.isAnonymous() || !pending.accountId().equals(caller.accountId()) || error != null || code == null
				|| code.isBlank() || state == null || !sameState(pending.state(), state)
				|| !this.clock.instant().isBefore(pending.startedAt().plus(LINK_LIFETIME))) {
			return failed("that-bai");
		}
		Answer answer = this.reader.read(code, pending.redirectUri(), pending.codeVerifier()).orElse(null);
		if (answer == null) {
			return failed("that-bai");
		}
		Outcome outcome;
		try {
			outcome = this.links.link(pending.accountId(), answer.user(), pending.purpose(), answer.token());
		}
		catch (DuplicateKeyException taken) {
			outcome = Outcome.ALREADY_LINKED;
		}
		return switch (outcome) {
			case LINKED, RELINKED -> pending.returnTo() != null ? pending.returnTo() : ACC_2 + "?ket-qua=da-lien-ket";
			case ALREADY_LINKED -> failed("da-lien-ket-tai-khoan-khac");
			case LINK_IN_USE -> failed("sai-tai-khoan");
			case ACCOUNT_INACTIVE -> failed("that-bai");
		};
	}

	private static String failed(String reason) {
		return ACC_2 + "?loi=" + reason;
	}

	/** Compared in constant time, so the comparison tells nothing about the session's {@code state}. */
	private static boolean sameState(String expected, String actual) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
	}

	/** 32 random bytes, base64url without padding: a {@code state}, or a verifier (RFC 7636 §4.1). */
	private String randomToken() {
		byte[] bytes = new byte[32];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** The S256 challenge of the verifier (RFC 7636 §4.2). */
	static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is missing", ex);
		}
	}

	/** {@code {baseUrl}} as Spring expands it: the origin the browser used, here through the Vite proxy. */
	private static String baseUrl(HttpServletRequest request) {
		return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
			.replacePath(request.getContextPath())
			.replaceQuery(null)
			.fragment(null)
			.build()
			.toUriString();
	}

}
