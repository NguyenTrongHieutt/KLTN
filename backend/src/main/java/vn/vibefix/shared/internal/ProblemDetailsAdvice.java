package vn.vibefix.shared.internal;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.RateLimitedException;

/**
 * Turns every error into RFC 9457 Problem Details (AD-23).
 * <ul>
 * <li>{@link ApiException}: its code's status, {@code code}, and {@code reason} when given; a
 * {@link RateLimitedException} adds {@code Retry-After} (AD-34).</li>
 * <li>Spring's own exceptions: {@code code} from the status ({@link ErrorCode#forStatus}).</li>
 * <li>Bean validation: {@code VALIDATION} plus {@code errors: [{field, code}]}, where the code is
 * the constraint name (for example {@code NotBlank}).</li>
 * <li>An exception annotated with {@code @ResponseStatus}: that status and its code.</li>
 * <li>Anything else: 500 without a code.</li>
 * </ul>
 * Every 5xx also carries {@code correlationId}, the request's ID from {@link CorrelationIdFilter},
 * which the logged error carries too, so the SPA can show it as a reference. Spring's English
 * {@code detail} text is removed: the backend returns codes, never sentences.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

	static final String CODE = "code";

	static final String REASON = "reason";

	static final String ERRORS = "errors";

	static final String CORRELATION_ID = "correlationId";

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

	/** One rejected field of a {@code VALIDATION} response. */
	record FieldViolation(String field, String code) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail body = ProblemDetail.forStatus(ex.code().status());
		body.setProperty(CODE, ex.code().name());
		ex.reason().ifPresent(reason -> body.setProperty(REASON, reason));
		addCorrelationId(body, ex.code().status());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.code().status());
		if (ex instanceof RateLimitedException limited) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
		}
		return response.body(body);
	}

	/**
	 * Everything else. An exception annotated with {@link ResponseStatus} keeps its status (and the
	 * code of that status); anything after the response was committed, such as a client that went
	 * away, is only logged.
	 */
	@ExceptionHandler(Exception.class)
	@Nullable ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletResponse response) {
		if (response.isCommitted()) {
			log.debug("Exception after the response was committed", ex);
			return null;
		}
		ResponseStatus declared = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
		HttpStatus status = declared == null ? HttpStatus.INTERNAL_SERVER_ERROR : declared.code();
		if (status.is5xxServerError()) {
			log.error("Unhandled exception", ex);
		}
		ProblemDetail body = ProblemDetail.forStatus(status);
		ErrorCode.forStatus(status.value()).ifPresent(code -> body.setProperty(CODE, code.name()));
		addCorrelationId(body, status.value());
		return ResponseEntity.status(status).body(body);
	}

	/** Logs the server errors Spring raises itself (for example an unwritable response body). */
	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		if (statusCode.is5xxServerError()) {
			log.error("Server error " + statusCode.value(), ex);
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail body = ex.getBody();
		body.setProperty(ERRORS, violations(ex.getBindingResult(), null));
		return handleExceptionInternal(ex, body, headers, status, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> violations = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			String parameter = parameterName(result.getMethodParameter().getParameterName());
			if (result instanceof ParameterErrors errors) {
				violations.addAll(violations(errors, parameter));
			}
			else {
				result.getResolvableErrors().forEach(error -> violations.add(new FieldViolation(parameter, codeOf(error))));
			}
		});
		ProblemDetail body = ex.getBody();
		// Return-value validation fails with 500: its field names are internals, so they stay out.
		if (status.is4xxClientError()) {
			body.setProperty(ERRORS, violations);
		}
		return handleExceptionInternal(ex, body, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			problem.setDetail(null);
			ErrorCode.forStatus(statusCode.value()).ifPresent(code -> problem.setProperty(CODE, code.name()));
			addCorrelationId(problem, statusCode.value());
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	/** A server error carries the request's correlation ID, so a user can quote it (Conventions, Logging). */
	private static void addCorrelationId(ProblemDetail body, int status) {
		String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
		if (status >= 500 && correlationId != null) {
			body.setProperty(CORRELATION_ID, correlationId);
		}
	}

	private static List<FieldViolation> violations(Errors result, @Nullable String parameter) {
		List<FieldViolation> violations = new ArrayList<>();
		for (FieldError error : result.getFieldErrors()) {
			violations.add(new FieldViolation(error.getField(), codeOf(error)));
		}
		result.getGlobalErrors()
			.forEach(error -> violations.add(new FieldViolation(parameter == null ? error.getObjectName() : parameter,
					codeOf(error))));
		return violations;
	}

	private static String parameterName(@Nullable String name) {
		return name == null ? "" : name;
	}

	/** The most general message code, which for bean validation is the constraint name. */
	private static String codeOf(MessageSourceResolvable error) {
		String[] codes = error.getCodes();
		return codes == null || codes.length == 0 ? "Invalid" : codes[codes.length - 1];
	}

}
