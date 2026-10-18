package vn.vibefix.shared.internal;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * AD-21: the security headers of every API response. Caddy sets the same values on the SPA's
 * responses only (deploy/caddy/Caddyfile), so no response carries them twice. A later handler may
 * still replace one, for example {@code Content-Security-Policy: sandbox} on a file download.
 * <p>
 * Besides the AD-21 set and {@code nosniff}, the cross-origin isolation headers (COOP, COEP, CORP)
 * keep other origins from embedding or opening these responses; the OWASP ZAP baseline in CI asks
 * for them. Everything the SPA loads is same-origin, which they require.
 * <p>
 * Story 1.5 may replace this filter with Spring Security's header writers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SecurityHeadersFilter extends OncePerRequestFilter {

	static final String CONTENT_SECURITY_POLICY = "default-src 'self'; object-src 'none'; base-uri 'none'; "
			+ "form-action 'self'; frame-ancestors 'none'";

	static final String PERMISSIONS_POLICY = "camera=(), geolocation=(), microphone=(), payment=(), usb=()";

	static final Map<String, String> HEADERS = Map.of("Content-Security-Policy", CONTENT_SECURITY_POLICY,
			"Strict-Transport-Security", "max-age=31536000; includeSubDomains", "Referrer-Policy",
			"strict-origin-when-cross-origin", "Permissions-Policy", PERMISSIONS_POLICY, "X-Content-Type-Options",
			"nosniff", "Cross-Origin-Opener-Policy", "same-origin", "Cross-Origin-Embedder-Policy", "require-corp",
			"Cross-Origin-Resource-Policy", "same-origin");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HEADERS.forEach(response::setHeader);
		chain.doFilter(request, response);
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

}
