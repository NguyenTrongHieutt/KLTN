package vn.vibefix.identity.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;

/**
 * Finishes a GitHub sign-in (AD-19). The GitHub principal Spring built is replaced at once, so the
 * session never holds GitHub's profile or a token:
 * <ul>
 * <li>an active account signs in, as its account ID and role, and returns to the route it asked for
 * or lands on its role's first screen;</li>
 * <li>a locked account is refused, and PUB-2 shows the reason;</li>
 * <li>a new GitHub account goes to PUB-3 to choose its role, as a pending sign-up.</li>
 * </ul>
 */
class SignInSuccessHandler implements AuthenticationSuccessHandler {

	private final SignIns signIns;

	private final SecurityContextRepository contexts;

	SignInSuccessHandler(SignIns signIns, SecurityContextRepository contexts) {
		this.signIns = signIns;
		this.contexts = contexts;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		OAuth2User user = (OAuth2User) authentication.getPrincipal();
		long githubUserId = ((Number) user.getAttributes().get("id")).longValue();
		String login = (String) user.getAttributes().get("login");
		String avatar = (String) user.getAttributes().get("avatar_url");
		String token = (String) request.getAttribute(TokenCapturingUserService.TOKEN_ATTRIBUTE);
		request.removeAttribute(TokenCapturingUserService.TOKEN_ATTRIBUTE);
		String email = (String) request.getAttribute(TokenCapturingUserService.EMAIL_ATTRIBUTE);
		request.removeAttribute(TokenCapturingUserService.EMAIL_ATTRIBUTE);

		HttpSession session = request.getSession();
		SignInSession.clear(session);
		String returnTo = (String) session.getAttribute(SignInSession.RETURN_TO);
		session.removeAttribute(SignInSession.RETURN_TO);

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		String target = switch (this.signIns.signIn(githubUserId, login, avatar, email, token)) {
			case SignIns.Outcome.SignedIn signedIn -> {
				context.setAuthentication(new AccountAuthentication(signedIn.account().id(), signedIn.account().role()));
				yield returnTo != null ? returnTo : SignIns.landingPath(signedIn.account());
			}
			case SignIns.Outcome.Locked locked -> {
				session.setAttribute(SignInSession.LOCKED, new SignInSession.Locked(locked.reason()));
				yield "/dang-nhap";
			}
			case SignIns.Outcome.NeedsRole needsRole -> {
				session.setAttribute(SignInSession.PENDING_SIGN_UP, needsRole.pending());
				yield "/bat-dau";
			}
		};
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		response.sendRedirect(target);
	}

}
