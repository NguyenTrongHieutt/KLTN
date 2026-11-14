package vn.vibefix.github.internal;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import vn.vibefix.shared.Access;
import vn.vibefix.shared.Gate;

/**
 * {@code POST /github/actions/report} (AD-11, AD-44, AD-20's caller "Actions run", Story 23.8a): a
 * {@code handover-copy} run posts what its range changed, between its fetch and its push. Like the exchange it
 * carries no session and no CSRF token, so the gate lets anyone in and the ID token proves the caller
 * ({@link ActionsReports}). The body is {@code {id_token, correlation, workflows_changed, paths, count}}, at most
 * {@value #MAX_BODY_BYTES} bytes (413 above), where {@code paths} is the base64 of the gzipped NUL-separated
 * UTF-8 paths. It answers 204; a refusal is 403 Problem Details with {@code FORBIDDEN} and its reason, and a call
 * over the abuse limits 429 {@code RATE_LIMITED} with {@code Retry-After}. Neither the body nor any path of it is
 * ever logged.
 */
@RestController
@Access(Gate.PUBLIC)
class ActionsReportController {

	static final String PATH = "/github/actions/report";

	/** AD-11 [ASSUMPTION]: 2 MB holds the compressed paths of a handover that changes 100,000 files. */
	static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	private final ActionsReports reports;

	ActionsReportController(ActionsReports reports) {
		this.reports = reports;
	}

	@PostMapping(PATH)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void report(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_BODY_BYTES + 1);
		}
		if (body.length > MAX_BODY_BYTES) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
		}
		this.reports.report(body, request.getRemoteAddr());
	}

}
