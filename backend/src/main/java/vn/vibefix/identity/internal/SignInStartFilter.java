package vn.vibefix.identity.internal;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs when a sign-in starts at {@code /oauth2/authorization/github}. It keeps the route the SPA
 * wants to return to ({@code quay-lai}) in the session, but only a plain relative path, so a link can
 * never send a signed-in user to another site. When this environment has no sign-in OAuth App, it
 * sends the visitor back to PUB-2 with {@code loi=chua-thiet-lap} instead.
 */
class SignInStartFilter extends OncePerRequestFilter {

	static final String START_PATH = "/oauth2/authorization/" + GitHubSignIn.REGISTRATION_ID;

	static final String RETURN_PARAMETER = "quay-lai";

	private static final Pattern SAFE_PATH = Pattern.compile("/(?![/\\\\])[A-Za-z0-9/_\\-.~%?=&]{0,200}");

	private final boolean configured;

	SignInStartFilter(boolean configured) {
		this.configured = configured;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !START_PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!this.configured) {
			response.sendRedirect("/dang-nhap?loi=chua-thiet-lap");
			return;
		}
		String returnTo = safeReturnPath(request.getParameter(RETURN_PARAMETER));
		if (returnTo != null) {
			request.getSession().setAttribute(SignInSession.RETURN_TO, returnTo);
		}
		else if (request.getSession(false) != null) {
			request.getSession().removeAttribute(SignInSession.RETURN_TO);
		}
		chain.doFilter(request, response);
	}

	/** {@code path} when it is a relative path inside the SPA, else {@code null}. */
	static @Nullable String safeReturnPath(@Nullable String path) {
		return path != null && SAFE_PATH.matcher(path).matches() ? path : null;
	}

}
