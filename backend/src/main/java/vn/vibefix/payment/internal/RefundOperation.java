package vn.vibefix.payment.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.operations.Lane;
import vn.vibefix.operations.OperationHandler;
import vn.vibefix.operations.OperationRun;
import vn.vibefix.operations.Outcome;

/**
 * {@value #TYPE} (AD-6, AD-15): carries out one refund through the gateway fixed on it, in the VNPay
 * lane. A confirmed refund answers at once, so a replay confirms nothing twice. No answer is retried with
 * the same request; a refusal fails at once into the FR-64 queue. There is no read-back: VNPay's signed
 * answer is the result. Each gateway call is counted on the refund, so the Customer hears of the
 * confirmation only when the first call did not confirm it. {@code Refunds} is looked up on use:
 * {@code operations} collects its handlers before the refunds that request them exist.
 */
@Component
class RefundOperation implements OperationHandler {

	static final String TYPE = "payment.refund";

	private final ObjectProvider<Refunds> refunds;

	private final List<RefundGateway> gateways;

	RefundOperation(ObjectProvider<Refunds> refunds, List<RefundGateway> gateways) {
		this.refunds = refunds;
		this.gateways = gateways;
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
	public Outcome run(OperationRun run) {
		UUID id = UUID.fromString(String.valueOf(run.payload().get("refundId")));
		Refunds refunds = this.refunds.getObject();
		Refunds.Refund refund = refunds.byId(id).orElse(null);
		if (refund == null) {
			return new Outcome.Obsolete("REFUND_GONE");
		}
		if ("CONFIRMED".equals(refund.status())) {
			return new Outcome.Succeeded(Map.of("status", "CONFIRMED"));
		}
		RefundGateway gateway = this.gateways.stream()
			.filter(found -> found.key().equals(refund.gateway()))
			.findFirst()
			.orElse(null);
		if (gateway == null) {
			return new Outcome.Aborted("GATEWAY_UNAVAILABLE");
		}
		int earlierCalls = refunds.countCall(id);
		return switch (gateway.refund(refund)) {
			case RefundGateway.Result.Confirmed confirmed -> {
				refunds.confirm(id, confirmed.transactionNo(), earlierCalls > 0);
				yield new Outcome.Succeeded(Map.of("status", "CONFIRMED"));
			}
			case RefundGateway.Result.Unanswered unanswered -> new Outcome.Transient(unanswered.code());
			case RefundGateway.Result.Refused refused -> new Outcome.Refused(refused.code());
		};
	}

}
