package vn.vibefix.files.internal;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import vn.vibefix.shared.ErrorCode;

/**
 * An upload above Spring's multipart limit ({@code spring.servlet.multipart}) is refused before any
 * controller runs; it answers as {@link vn.vibefix.files.StoredFiles#prepare} would, {@code VALIDATION} with
 * {@code FILE_TOO_LARGE}, ahead of the shared Problem Details advice.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UploadLimitAdvice {

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ProblemDetail> tooLarge() {
		ProblemDetail body = ProblemDetail.forStatus(ErrorCode.VALIDATION.status());
		body.setProperty("code", ErrorCode.VALIDATION.name());
		body.setProperty("reason", FilesReason.FILE_TOO_LARGE.name());
		return ResponseEntity.status(ErrorCode.VALIDATION.status()).body(body);
	}

}
