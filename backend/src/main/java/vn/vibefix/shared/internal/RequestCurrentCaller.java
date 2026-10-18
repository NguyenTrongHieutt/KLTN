package vn.vibefix.shared.internal;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;

/**
 * Reads the caller from the request attribute {@link Caller#REQUEST_ATTRIBUTE}. No sign-in exists
 * yet, so nothing sets it and every request is anonymous; the sign-in filter will set it once it has
 * resolved the session. {@link AccessWebConfiguration} declares the bean, so that every web context,
 * test slices included, has it next to the gate that reads it.
 */
class RequestCurrentCaller implements CurrentCaller {

	@Override
	public Caller get() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (request == null) {
			throw new IllegalStateException("There is no caller outside a request");
		}
		return request.getAttribute(Caller.REQUEST_ATTRIBUTE,
				RequestAttributes.SCOPE_REQUEST) instanceof Caller caller ? caller : Caller.anonymous();
	}

}
