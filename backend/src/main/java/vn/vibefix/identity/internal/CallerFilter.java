package vn.vibefix.identity.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

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
import vn.vibefix.shared.Role;

/**
 * Turns the session's account into the request's {@link Caller} (AD-20), after re-reading the
 * account from the database on every request (AD-19): an account that is locked, erased, or gone
 * loses its session at once and the request continues as anonymous. An Administrator session also
 * ends after 30 minutes without a command or 8 hours after sign-in (SG-10); only a command, never a
 * read such as a poll, counts as activity.
 */
class CallerFilter extends OncePerRequestFilter {

	/** Requests that read; they never extend an Administrator session. */
	static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

	private final Accounts accounts;

	private final Clock clock;

	CallerFilter(Accounts accounts, Clock clock) {
		this.accounts = accounts;
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof AccountAuthentication signedIn) {
			Optional<Account> account = this.accounts.findById(signedIn.accountId());
			if (account.isPresent() && account.get().isActive() && withinAdministratorLimits(signedIn, request)) {
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

	/** True for any other role; for an Administrator, within the limits, and a command extends it. */
	private boolean withinAdministratorLimits(AccountAuthentication signedIn, HttpServletRequest request) {
		if (signedIn.role() != Role.ADMINISTRATOR) {
			return true;
		}
		HttpSession session = request.getSession(false);
		Instant now = this.clock.instant();
		if (AdminSessions.current(session, now) == null) {
			return false;
		}
		if (!READS.contains(request.getMethod())) {
			AdminSessions.touch(session, now);
		}
		return true;
	}

}
