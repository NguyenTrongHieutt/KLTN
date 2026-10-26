package vn.vibefix.identity.internal;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import vn.vibefix.shared.ErrorCode;

/**
 * Limits sign-in attempts per client address and minute (AD-19, AD-34): a per-minute window in
 * memory, so the count is per application instance and starts again on restart. The address is the
 * connection's remote address until Caddy is in front (Story 18.4). A refused attempt answers
 * {@code 429 RATE_LIMITED} with {@code Retry-After}.
 */
class SignInRateLimiter extends OncePerRequestFilter {

	/** The Google sign-in's start and callback, and the GitHub link's callback (Story 19.2). */
	static final String[] PATHS = { "/oauth2/authorization/", "/login/oauth2/code/", "/link/oauth2/code/" };

	/** Windows kept before stale ones are dropped, so a flood of addresses cannot grow memory without end. */
	static final int MAX_TRACKED = 10_000;

	private record Window(long minute, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private final Clock clock;

	private final int limitPerMinute;

	SignInRateLimiter(Clock clock, int limitPerMinute) {
		this.clock = clock;
		this.limitPerMinute = limitPerMinute;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		for (String prefix : PATHS) {
			if (path.startsWith(prefix)) {
				return false;
			}
		}
		return true;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long now = this.clock.millis();
		long minute = now / 60_000;
		if (!allow(request.getRemoteAddr(), minute)) {
			long retryAfter = Math.max(1, (minute + 1) * 60 - now / 1000);
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
			SecurityProblems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
			return;
		}
		chain.doFilter(request, response);
	}

	/** Counts one attempt from {@code address} in {@code minute}; false once the limit is passed. */
	boolean allow(String address, long minute) {
		if (this.windows.size() > MAX_TRACKED) {
			this.windows.values().removeIf(window -> window.minute() < minute);
		}
		Window window = this.windows.merge(address, new Window(minute, 1),
				(old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
		return window.count() <= this.limitPerMinute;
	}

}
