package vn.vibefix.shared.internal.sandbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.SandboxJobHandler;
import vn.vibefix.shared.SandboxJobRequest;
import vn.vibefix.shared.SandboxJobResult;
import vn.vibefix.shared.internal.CorrelationIdFilter;

/**
 * The sandbox worker's only endpoint: runs one job with the handler for its type. It exists only in
 * the worker ({@code @Profile("sandbox")}, imported by {@link SandboxWorkerConfiguration}), never in
 * the application.
 * <p>
 * Every handler is served unless {@value #JOB_TYPES_PROPERTY} lists the job types this worker may
 * run: the {@code checker} container lists only what outbound checks need, so a job meant for the
 * sandbox never runs where public hosts are reachable. A listed type without a handler, or a list
 * that is set but names no type, refuses the start: a blanked list must never mean "everything".
 * <p>
 * Log lines carry the correlation ID of the application request that sent the job, when it sent a
 * well-formed one. A missing type or invalid input answers 400 {@code VALIDATION}, an unknown or
 * unlisted type 404 {@code NOT_FOUND}, and a failed job (or one that returned no output) 500.
 */
@RestController
@Profile(SandboxWorkerConfiguration.PROFILE)
class SandboxJobController {

	static final String PATH = "/internal/sandbox/jobs";

	static final String JOB_TYPES_PROPERTY = "vibefix.sandbox.worker.job-types";

	private static final Logger log = LoggerFactory.getLogger(SandboxJobController.class);

	private final Map<String, SandboxJobHandler> handlers;

	SandboxJobController(List<SandboxJobHandler> handlers,
			@Value("${" + JOB_TYPES_PROPERTY + ":#{null}}") @Nullable List<String> jobTypes) {
		Map<String, SandboxJobHandler> byType = new HashMap<>();
		for (SandboxJobHandler handler : handlers) {
			SandboxJobHandler previous = byType.putIfAbsent(handler.type(), handler);
			if (previous != null) {
				throw new IllegalStateException("Sandbox job type " + handler.type() + " has two handlers: "
						+ previous.getClass().getName() + " and " + handler.getClass().getName());
			}
		}
		this.handlers = Map.copyOf(jobTypes == null ? byType : listedOnly(byType, jobTypes));
	}

	private static Map<String, SandboxJobHandler> listedOnly(Map<String, SandboxJobHandler> byType,
			List<String> jobTypes) {
		Map<String, SandboxJobHandler> listed = new HashMap<>();
		for (String jobType : jobTypes) {
			String type = jobType.strip();
			if (type.isEmpty()) {
				continue;
			}
			SandboxJobHandler handler = byType.get(type);
			if (handler == null) {
				throw new IllegalStateException(
						JOB_TYPES_PROPERTY + " lists " + type + ", which no sandbox job handler runs; refusing to start");
			}
			listed.put(type, handler);
		}
		if (listed.isEmpty()) {
			throw new IllegalStateException(JOB_TYPES_PROPERTY + " is set but lists no job type; refusing to start");
		}
		return listed;
	}

	@PostMapping(PATH)
	ResponseEntity<?> run(@RequestBody SandboxJobRequest request,
			@RequestHeader(name = CorrelationIdFilter.HEADER, required = false) @Nullable String correlationId) {
		MDC.put(CorrelationIdFilter.MDC_KEY, correlationIdOrNew(correlationId));
		try {
			if (request.type() == null || request.type().isBlank()) {
				return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION);
			}
			SandboxJobHandler handler = this.handlers.get(request.type());
			if (handler == null) {
				return problem(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
			}
			JsonNode input = request.input() == null || request.input().isNull() ? JsonNodeFactory.instance.objectNode()
					: request.input();
			try {
				JsonNode output = handler.handle(input);
				if (output == null) {
					log.error("Sandbox job {} returned no output", request.type());
					return problem(HttpStatus.INTERNAL_SERVER_ERROR, null);
				}
				return ResponseEntity.ok(new SandboxJobResult(request.type(), output));
			}
			catch (IllegalArgumentException ex) {
				log.warn("Sandbox job {} refused its input: {}", request.type(), ex.getMessage());
				return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION);
			}
			catch (RuntimeException ex) {
				log.error("Sandbox job {} failed", request.type(), ex);
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, null);
			}
		}
		finally {
			MDC.remove(CorrelationIdFilter.MDC_KEY);
		}
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, @Nullable ErrorCode code) {
		ProblemDetail body = ProblemDetail.forStatus(status);
		if (code != null) {
			body.setProperty("code", code.name());
		}
		return ResponseEntity.status(status).body(body);
	}

	/** Only a well-formed UUID is taken over, so a header can never inject text into the logs. */
	private static String correlationIdOrNew(@Nullable String header) {
		if (header != null) {
			try {
				return UUID.fromString(header.strip()).toString();
			}
			catch (IllegalArgumentException ex) {
				// Not an ID this platform issued: use a new one.
			}
		}
		return UUID.randomUUID().toString();
	}

}
