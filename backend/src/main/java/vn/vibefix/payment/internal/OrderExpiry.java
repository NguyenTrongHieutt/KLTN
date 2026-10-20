package vn.vibefix.payment.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import vn.vibefix.timers.DeadlineHandler;
import vn.vibefix.timers.DeadlineKind;
import vn.vibefix.timers.DeadlineOutcome;
import vn.vibefix.timers.DueDeadline;

/**
 * {@value PaymentOrders#EXPIRY} (AD-14): 15 minutes after its creation, an order still open expires and
 * the Customer is told; a paid, failed, or voided one leaves the Deadline {@code OBSOLETE}. Reconciling
 * with {@code querydr} waits for spike S2. Not pausable: a Dispute freezes new orders, not this one.
 */
@Component
class OrderExpiry implements DeadlineHandler {

	static final DeadlineKind KIND = new DeadlineKind(PaymentOrders.EXPIRY, false, true);

	private final ObjectProvider<PaymentOrders> orders;

	OrderExpiry(ObjectProvider<PaymentOrders> orders) {
		this.orders = orders;
	}

	@Override
	public DeadlineKind kind() {
		return KIND;
	}

	@Override
	public DeadlineOutcome handle(DueDeadline deadline) {
		return this.orders.getObject().expire(deadline.subjectId()) ? DeadlineOutcome.FIRED : DeadlineOutcome.OBSOLETE;
	}

}
