package vn.vibefix.shared.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * Denies by default (AD-20): before a handler under {@code /api} or {@code /webhooks} runs, its
 * {@link Access} gate must let the caller through. A handler that declares no gate is a
 * programming error; it answers {@code 404 NOT_FOUND} to everyone and logs the handler.
 */
class AccessGateInterceptor implements HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(AccessGateInterceptor.class);

	private final CurrentCaller callers;

	AccessGateInterceptor(CurrentCaller callers) {
		this.callers = callers;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		Gate gate = gateOf(method);
		if (gate == null) {
			log.error("Handler {} declares no @Access gate; it is refused until it does", method.getShortLogMessage());
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		Caller caller = this.callers.get();
		switch (gate) {
			case PUBLIC -> {
			}
			case SIGNED_IN -> requireSignedIn(caller);
			case ADMINISTRATOR -> {
				requireSignedIn(caller);
				if (!caller.isAdministrator()) {
					throw new ApiException(ErrorCode.NOT_FOUND);
				}
			}
		}
		return true;
	}

	/** The method's gate, else its controller's. */
	static @Nullable Gate gateOf(HandlerMethod method) {
		Access access = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Access.class);
		if (access == null) {
			access = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), Access.class);
		}
		return access == null ? null : access.value();
	}

	private static void requireSignedIn(Caller caller) {
		if (caller.isAnonymous()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
	}

}
