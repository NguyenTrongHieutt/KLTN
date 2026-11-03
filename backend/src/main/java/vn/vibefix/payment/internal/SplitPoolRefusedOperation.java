package vn.vibefix.payment.internal;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * {@value #TYPE} (AD-15, FR-64): carries a final split that {@link SplitPoolExecution} refused into the failed-operations
 * queue, where an Administrator looks at it. It has one attempt, which runs the executor again and fails with the refusal's
 * reason as its error code, so the queue shows why, and alerts Administrators; FR-64's retry, under its step-up and audit,
 * runs it again once the cause is fixed. A split already paid writes nothing. Its payload holds no percentage: the
 * executor reads them from {@code job}.
 */
@Component
class SplitPoolRefusedOperation implements OperationHandler {

	static final String TYPE = "payment.split_pool_refused";

	private final ObjectProvider<SplitPoolExecution> execution;

	SplitPoolRefusedOperation(ObjectProvider<SplitPoolExecution> execution) {
		this.execution = execution;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public Lane lane() {
		return Lane.VNPAY;
	}

	@Override
	public OperationPolicy policy() {
		return new OperationPolicy(1, Duration.ofMinutes(1));
	}

	@Override
	public Outcome run(OperationRun run) {
		UUID milestoneId = UUID.fromString(String.valueOf(run.payload().get("milestoneId")));
		String refused = String.valueOf(run.payload().get("reason"));
		return this.execution.getObject()
			.reRun(milestoneId, refused)
			.<Outcome>map(Outcome.Transient::new)
			.orElseGet(() -> new Outcome.Succeeded(Map.of("result", "PAID")));
	}

}
