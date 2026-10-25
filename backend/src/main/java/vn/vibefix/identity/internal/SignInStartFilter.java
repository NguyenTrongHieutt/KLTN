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
 * Runs when a sign-in starts under {@code /oauth2/authorization/}. For the Google sign-in it keeps
 * the route the SPA wants to return to ({@code quay-lai}) in the session, but only a plain relative
 * path, so a link can never send a signed-in user to another site; when this environment has no
 * Google client, it sends the visitor back to PUB-2 with {@code loi=chua-thiet-lap} instead. Any other
 * start, the GitHub one of before Story 19.1 included, signs nobody in and returns to PUB-2 with
 * {@code loi=that-bai}.
 */
class SignInStartFilter extends OncePerRequestFilter {

	static final String AUTHORIZATION_PATH = "/oauth2/authorization/";

	static final String START_PATH = AUTHORIZATION_PATH + GoogleSignIn.REGISTRATION_ID;

	static final String RETURN_PARAMETER = "quay-lai";

	private static final Pattern SAFE_PATH = Pattern.compile("/(?![/\\\\])[A-Za-z0-9/_\\-.~%?=&]{0,200}");

	private final boolean configured;

	SignInStartFilter(boolean configured) {
		this.configured = configured;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(AUTHORIZATION_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!START_PATH.equals(request.getRequestURI())) {
			response.sendRedirect("/dang-nhap?loi=that-bai");
			return;
		}
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
