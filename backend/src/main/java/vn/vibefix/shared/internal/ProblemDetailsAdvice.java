package vn.vibefix.shared.internal;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
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
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * Turns every error into RFC 9457 Problem Details (AD-23).
 * <ul>
 * <li>{@link ApiException}: its code's status, {@code code}, and {@code reason} when given.</li>
 * <li>Spring's own exceptions: {@code code} from the status ({@link ErrorCode#forStatus}).</li>
 * <li>Bean validation: {@code VALIDATION} plus {@code errors: [{field, code}]}, where the code is
 * the constraint name (for example {@code NotBlank}).</li>
 * <li>Anything else: 500 without a code.</li>
 * </ul>
 * Spring's English {@code detail} text is removed: the backend returns codes, never sentences.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

	static final String CODE = "code";

	static final String REASON = "reason";

	static final String ERRORS = "errors";

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

	/** One rejected field of a {@code VALIDATION} response. */
	record FieldViolation(String field, String code) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail body = ProblemDetail.forStatus(ex.code().status());
		body.setProperty(CODE, ex.code().name());
		ex.reason().ifPresent(reason -> body.setProperty(REASON, reason));
		return ResponseEntity.status(ex.code().status()).body(body);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(status).body(ProblemDetail.forStatus(status));
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
		body.setProperty(ERRORS, violations);
		return handleExceptionInternal(ex, body, headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem) {
			problem.setDetail(null);
			ErrorCode.forStatus(statusCode.value()).ifPresent(code -> problem.setProperty(CODE, code.name()));
		}
		return super.createResponseEntity(body, headers, statusCode, request);
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
