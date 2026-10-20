package vn.vibefix.payment.internal;

import java.time.Clock;
import java.util.Map;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.AgreementConfirmed.ConfirmedMilestone;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The confirmed Agreement (FR-17, FR-41): each Milestone gets its ledger account, and the Customer's
 * "Thanh toán Giai đoạn 1: {amount} THỬ NGHIỆM" opens (UX-DR15). Replay-safe.
 */
@Component
class PaymentListeners {

	private final PaymentOrders orders;

	private final Todos todos;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	PaymentListeners(PaymentOrders orders, Todos todos, ProcessedEvents processedEvents, Clock clock) {
		this.orders = orders;
		this.todos = todos;
		this.processedEvents = processedEvents;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onAgreementConfirmed(AgreementConfirmed event) {
		if (!this.processedEvents.markProcessed("payment.PaymentListeners.onAgreementConfirmed", event.eventId())) {
			return;
		}
		for (ConfirmedMilestone milestone : event.milestones()) {
			this.orders.ensureAccount(milestone.milestoneId(), event.jobId(), event.productId(), this.clock.instant());
		}
		event.milestones().stream().findFirst().ifPresent(first -> this.todos.open(new TodoRequest(PaymentOrders.FUND_TODO,
				event.customerId(), Audience.CUSTOMER, first.milestoneId(),
				Map.of("n", String.valueOf(first.number()), "amount", PaymentOrders.vnd(first.priceVnd())),
				"/cong-viec/" + event.jobId() + "/thanh-toan", null, event.jobId())));
	}

}
