package vn.vibefix.shared.internal;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Conventions, Logging: every request gets a new correlation ID, which every log line written while
 * it is handled carries (MDC key {@value #MDC_KEY}, a field of the JSON log line), the response
 * returns in {@value #HEADER}, and 5xx Problem Details carry as {@code correlationId} so a user can
 * quote it. An ID sent by the client is ignored: it would let anyone forge log correlations.
 * <p>
 * The filter runs first, and again on the async and error dispatches with the same ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = request.getAttribute(ATTRIBUTE) instanceof String existing ? existing : UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, id);
		response.setHeader(HEADER, id);
		MDC.put(MDC_KEY, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

}
