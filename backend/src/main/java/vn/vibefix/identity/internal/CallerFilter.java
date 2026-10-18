package vn.vibefix.identity.internal;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import vn.vibefix.identity.internal.SignInSession.AccountAuthentication;
import vn.vibefix.shared.Caller;

/**
 * Turns the session's account into the request's {@link Caller} (AD-20), after re-reading the
 * account from the database on every request (AD-19): an account that is locked, erased, or gone
 * loses its session at once and the request continues as anonymous.
 */
class CallerFilter extends OncePerRequestFilter {

	private final Accounts accounts;

	CallerFilter(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof AccountAuthentication signedIn) {
			Optional<Account> account = this.accounts.findById(signedIn.accountId());
			if (account.isPresent() && account.get().isActive()) {
				request.setAttribute(Caller.REQUEST_ATTRIBUTE, Caller.account(signedIn.accountId(), signedIn.role()));
			}
			else {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
		}
		chain.doFilter(request, response);
	}

}
