package vn.vibefix.payment.internal;

import java.time.Duration;

import org.springframework.stereotype.Component;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationPolicy;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * {@value #TYPE} (AD-15): carries a final split that {@link SplitPoolExecution} refused into the failed-operations queue,
 * where an Administrator looks at it (FR-64). It has one attempt and always fails, which alerts Administrators.
 */
@Component
class SplitPoolRefusedOperation implements OperationHandler {

	static final String TYPE = "payment.split_pool_refused";

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
		return new Outcome.Transient("SPLIT_POOL_REFUSED");
	}

}
