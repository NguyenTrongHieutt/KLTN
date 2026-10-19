package vn.vibefix.scanning.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationCompleted;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.OperationStatus;
import vn.vibefix.operations.Outcome;
import vn.vibefix.scanning.ScanStatus;
import vn.vibefix.scanning.internal.ScanRuns.Run;
import vn.vibefix.shared.SandboxJobException;
import vn.vibefix.shared.SandboxJobs;

/**
 * {@value JdbcScans#OPERATION_TYPE} (AD-18, AD-6): runs one scan run's {@value SecretScanJobHandler#TYPE}
 * job in the sandbox and records the result. A sandbox failure or a timeout is tried again, 3
 * attempts 5 minutes apart; a repository the scan refuses, or a cap that stopped it, fails the run at
 * once, since another attempt finds the same. A failed operation waits in the Administrator queue
 * (FR-64), and its completion fails the run here.
 */
@Component
class ScanOperationHandler implements OperationHandler {

	static final OperationPolicy POLICY = new OperationPolicy(3, Duration.ofMinutes(5));

	static final String SCAN_JOB_FAILED = "SCAN_JOB_FAILED";

	static final String SCAN_TIMEOUT = "SCAN_TIMEOUT";

	private final ScanRuns runs;

	/** Looked up per run: it sends notifications, whose emails are operations, so a constructor cycle. */
	private final ObjectProvider<ScanCompletion> completion;

	private final SandboxJobs sandbox;

	private final Clock clock;

	ScanOperationHandler(ScanRuns runs, ObjectProvider<ScanCompletion> completion, SandboxJobs sandbox, Clock clock) {
		this.runs = runs;
		this.completion = completion;
		this.sandbox = sandbox;
		this.clock = clock;
	}

	@Override
	public String type() {
		return JdbcScans.OPERATION_TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.SCAN;
	}

	@Override
	public OperationPolicy policy() {
		return POLICY;
	}

	@Override
	public Outcome run(OperationRun operation) {
		Optional<Run> found = this.runs.find(scanRunId(operation.payload()));
		if (found.isEmpty()) {
			return new Outcome.Obsolete("SCAN_RUN_GONE");
		}
		Run run = found.get();
		if (run.status() == ScanStatus.SUCCEEDED) {
			return Outcome.succeeded();
		}
		this.runs.started(run.id(), this.clock.instant());
		ObjectNode input = JsonNodeFactory.instance.objectNode();
		input.put("repository", run.repositoryFullName());
		ScanLimits.DEFAULT.write(input.putObject("limits"));
		JsonNode output;
		try {
			output = this.sandbox.run(SecretScanJobHandler.TYPE, input).output();
		}
		catch (SandboxJobException ex) {
			this.runs.retrying(run.id(), SCAN_JOB_FAILED);
			return new Outcome.Transient(SCAN_JOB_FAILED);
		}
		String refused = text(output, "refused");
		String stopped = text(output, "stopped");
		if (HistoryScanner.TIMEOUT.equals(refused) || HistoryScanner.TIMEOUT.equals(stopped)) {
			this.runs.retrying(run.id(), SCAN_TIMEOUT);
			return new Outcome.Transient(SCAN_TIMEOUT);
		}
		if (refused != null) {
			String code = refused.matches("[A-Z][A-Z0-9_]*") ? refused : SCAN_JOB_FAILED;
			this.runs.failed(run.id(), code, this.clock.instant());
			return new Outcome.Aborted(code);
		}
		if (stopped != null && !stopped.matches("[A-Z][A-Z0-9_]*")) {
			stopped = SCAN_JOB_FAILED;
		}
		this.completion.getObject().record(run, output, stopped);
		return stopped == null ? Outcome.succeeded() : new Outcome.Aborted(stopped);
	}

	@Override
	public Optional<Map<String, Object>> readBack(OperationRun operation) {
		return this.runs.find(scanRunId(operation.payload()))
			.filter(run -> run.status() == ScanStatus.SUCCEEDED)
			.map(run -> Map.<String, Object>of());
	}

	/** An operation that ended without success fails its run, unless an attempt recorded it. */
	@ApplicationModuleListener
	void on(OperationCompleted completed) {
		if (!JdbcScans.OPERATION_TYPE.equals(completed.type()) || completed.status() == OperationStatus.SUCCEEDED) {
			return;
		}
		UUID id = UUID.fromString(completed.key().substring(JdbcScans.OPERATION_TYPE.length() + 1));
		this.runs.failed(id, completed.errorCode() == null ? completed.status().name() : completed.errorCode(),
				this.clock.instant());
	}

	private static UUID scanRunId(Map<String, Object> payload) {
		return UUID.fromString(String.valueOf(payload.get("scanRunId")));
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.stringValue() : null;
	}

}
