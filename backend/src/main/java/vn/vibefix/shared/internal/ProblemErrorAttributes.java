package vn.vibefix.shared.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import vn.vibefix.shared.ErrorCode;

/**
 * What Boot's {@code /error} answers when an error happens outside a controller (a filter, the
 * servlet container): the same Problem Details shape as {@link ProblemDetailsAdvice}, with the code
 * of the status and, on a 5xx, the request's {@code correlationId} (AD-23, Conventions, Logging).
 * Boot's message, exception, and trace never reach the client.
 */
@Component
class ProblemErrorAttributes extends DefaultErrorAttributes {

	@Override
	public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
		Map<String, Object> defaults = super.getErrorAttributes(request, ErrorAttributeOptions.defaults());
		int status = defaults.get("status") instanceof Integer value ? value : 500;
		HttpStatus resolved = HttpStatus.resolve(status);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "about:blank");
		body.put("title", resolved == null ? "Error" : resolved.getReasonPhrase());
		body.put("status", status);
		if (defaults.get("path") instanceof String path) {
			body.put("instance", path);
		}
		ErrorCode.forStatus(status).ifPresent(code -> body.put(ProblemDetailsAdvice.CODE, code.name()));
		String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
		if (status >= 500 && correlationId != null) {
			body.put(ProblemDetailsAdvice.CORRELATION_ID, correlationId);
		}
		return body;
	}

}
