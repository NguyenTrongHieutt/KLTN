package vn.vibefix.identity.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;

/**
 * Finishes a Google sign-in (AD-19). The Google principal Spring built is replaced at once, so the
 * session never holds Google's ID token, its claims, or a token:
 * <ul>
 * <li>an account that accepted the current N-10 signs in, as its account ID (a Google account has no
 * role), and returns to the route it asked for or lands on the to-do list;</li>
 * <li>a locked account is refused, and PUB-2 shows the reason;</li>
 * <li>a first sign-in, or an account that has not accepted the current N-10, goes to PUB-3
 * "Chào mừng" holding no account until it accepts.</li>
 * </ul>
 * {@link GoogleUserService} has refused an unverified email before this runs.
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
		OidcUser user = (OidcUser) authentication.getPrincipal();
		String subject = user.getSubject();
		String email = user.getEmail();
		String name = user.getFullName();

		HttpSession session = request.getSession();
		SignInSession.clear(session);
		String returnTo = (String) session.getAttribute(SignInSession.RETURN_TO);
		session.removeAttribute(SignInSession.RETURN_TO);

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		String target = switch (this.signIns.signInWithGoogle(subject, email, name)) {
			case SignIns.Outcome.SignedIn signedIn -> {
				context.setAuthentication(new AccountAuthentication(signedIn.account().id(), signedIn.account().role()));
				yield returnTo != null ? returnTo : SignIns.landingPath(signedIn.account());
			}
			case SignIns.Outcome.Locked locked -> {
				session.setAttribute(SignInSession.LOCKED, new SignInSession.Locked(locked.reason()));
				yield "/dang-nhap";
			}
			case SignIns.Outcome.NeedsWelcome welcome -> {
				session.setAttribute(SignInSession.PENDING_WELCOME, welcome.pending());
				yield "/bat-dau";
			}
		};
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		response.sendRedirect(target);
	}

}
