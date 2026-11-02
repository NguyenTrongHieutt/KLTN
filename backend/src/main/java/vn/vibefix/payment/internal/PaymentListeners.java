package vn.vibefix.payment.internal;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import vn.vibefix.job.AgreementConfirmed;
import vn.vibefix.job.AgreementConfirmed.ConfirmedMilestone;
import vn.vibefix.job.CrewMemberAdded;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.TodoRequest;
import vn.vibefix.notification.Todos;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ProcessedEvents;

/**
 * The confirmed Agreement (FR-17, FR-41): each Milestone gets its ledger account, and with at least one signed Crew
 * member (R7), at the confirmation or at the first signature after it, the Customer's "Thanh toán Giai đoạn 1: {amount}
 * THỬ NGHIỆM" opens (UX-DR15); an accepted Milestone (M5) opens the same to-do for the next Planned one. A cancelled
 * Milestone (M6, M7) closes its funding to-do, and M7 refunds its held money in full (FR-40, FR-43). Replay-safe.
 */
@Component
class PaymentListeners {

	private final PaymentOrders orders;

	private final MilestoneFunding funding;

	private final Todos todos;

	private final CancelledRefunds refunds;

	private final ProcessedEvents processedEvents;

	private final Clock clock;

	PaymentListeners(PaymentOrders orders, MilestoneFunding funding, Todos todos, CancelledRefunds refunds,
			ProcessedEvents processedEvents, Clock clock) {
		this.orders = orders;
		this.refunds = refunds;
		this.funding = funding;
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
		if (event.agreementVersionNo() > 1) {
			// A later version (a Change Request, FR-36) opens the new Milestones' accounts only; Milestone 1 was funded.
			return;
		}
		event.milestones().stream().findFirst().filter(first -> !awaitsAMember(event.jobId(), first.milestoneId())).ifPresent(
				first -> this.todos.open(new TodoRequest(PaymentOrders.FUND_TODO, event.customerId(), Audience.CUSTOMER,
						first.milestoneId(), Map.of("n", String.valueOf(first.number()), "amount", PaymentOrders.vnd(first.priceVnd())),
						"/cong-viec/" + event.jobId() + "/thanh-toan", null, event.jobId())));
	}

	/** R7: a member signed on a confirmed Agreement still in Agreement, so Milestone 1 can be funded; a repeat adds nothing. */
	@ApplicationModuleListener
	void onCrewMemberAdded(CrewMemberAdded event) {
		if (!this.processedEvents.markProcessed("payment.PaymentListeners.onCrewMemberAdded", event.eventId())) {
			return;
		}
		this.funding.byId(event.jobId())
			.filter(job -> "AGREEMENT".equals(job.state()))
			.ifPresent(job -> job.milestones().stream().findFirst().ifPresent(first -> this.todos.open(new TodoRequest(
					PaymentOrders.FUND_TODO, job.customerId(), Audience.CUSTOMER, first.milestoneId(),
					Map.of("n", String.valueOf(first.number()), "amount", PaymentOrders.vnd(first.priceVnd())),
					"/cong-viec/" + job.jobId() + "/thanh-toan", null, job.jobId()))));
	}

	/** J6's guard (FR-75): Milestone 1 waits for a signed member. */
	private boolean awaitsAMember(UUID jobId, UUID milestoneId) {
		ApiException refusal = this.funding.refusal(jobId, milestoneId);
		return refusal != null && refusal.reason().filter("NO_SIGNED_CREW"::equals).isPresent();
	}

	@ApplicationModuleListener
	void onJobTransitioned(JobTransitioned event) {
		if (!"M5".equals(event.transition())
				|| !this.processedEvents.markProcessed("payment.PaymentListeners.onJobTransitioned", event.eventId())) {
			return;
		}
		this.funding.byId(event.jobId()).ifPresent(job -> job.milestones()
			.stream()
			.filter(milestone -> "PLANNED".equals(milestone.state()))
			.findFirst()
			.ifPresent(next -> this.todos.open(new TodoRequest(PaymentOrders.FUND_TODO, job.customerId(), Audience.CUSTOMER,
					next.milestoneId(), Map.of("n", String.valueOf(next.number()), "amount", PaymentOrders.vnd(next.priceVnd())),
					"/cong-viec/" + job.jobId() + "/thanh-toan", null, job.jobId()))));
	}

	@ApplicationModuleListener
	void onMilestoneCancelled(JobTransitioned event) {
		boolean held = "M7".equals(event.transition());
		String milestoneId = event.facts().get("milestoneId");
		if ((!held && !"M6".equals(event.transition())) || milestoneId == null
				|| !this.processedEvents.markProcessed("payment.PaymentListeners.onMilestoneCancelled", event.eventId())) {
			return;
		}
		UUID milestone = UUID.fromString(milestoneId);
		this.todos.closeAll(PaymentOrders.FUND_TODO, milestone);
		if (held) {
			this.refunds.refund(milestone);
		}
	}

}
