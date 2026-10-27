package vn.vibefix.payment.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.vibefix.job.JobRoles;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.payment.internal.PaymentOrders.Created;
import vn.vibefix.payment.internal.PaymentOrders.Order;
import vn.vibefix.shared.Access;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.CurrentCaller;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Gate;

/**
 * JOB-7 for the Customer and CUS-9 (FR-41, AD-14): the Milestones to fund with why one waits,
 * "Thanh toán", and an order's status, read from the server only; N-13's sandbox test card. Only the
 * Job's Customer finds anything, and only once its Agreement is confirmed.
 */
@RestController
@Access(Gate.SIGNED_IN)
class PaymentController {

	/**
	 * One Milestone of JOB-7 for the Customer: never a party's part (AD-20).
	 * @param releasedTotalVnd {@code payment.releasedTotal}, the only released figure a Customer sees
	 */
	/**
	 * One Milestone of JOB-7 for the Customer: never a party's part (AD-20).
	 * @param refundAtEndVnd held above a price a Change Request cut: "Sẽ hoàn {amount} THỬ NGHIỆM khi giai đoạn
	 * kết thúc" (FR-36)
	 */
	record FundingMilestoneView(UUID milestoneId, int number, String title, long priceVnd, String state, boolean fundable,
			@Nullable String blockedReason, long fundedVnd, long topUpVnd, long refundedVnd, long releasedTotalVnd,
			long refundAtEndVnd) {
	}

	/** The difference a waiting Change Request asks the Customer to pay (FR-36). */
	record TopUpView(UUID changeRequestId, int number, long amountVnd) {
	}

	/**
	 * The refund of money that could not apply (FR-43).
	 * @param simulated recorded on vibefix only ("Hoàn tiền mô phỏng", AD-15)
	 * @param reason {@code CREW_CHANGED}, {@code ORDER_CLOSED}, {@code WRONG_AMOUNT}, or {@code NOT_FUNDABLE}
	 */
	record PaymentRefundView(long amountVnd, String status, boolean simulated, String reason, Instant requestedAt,
			@Nullable Instant confirmedAt) {
	}

	record PaymentOrderView(UUID id, UUID milestoneId, int number, long amountVnd, String status, Instant createdAt,
			Instant expiresAt, @Nullable Instant finishedAt, String reference, @Nullable PaymentRefundView refund) {
	}

	/**
	 * JOB-7's funding.
	 * @param configured the environment has its VNPay sandbox merchant
	 */
	record JobFundingView(UUID jobId, String title, String state, long version, boolean configured,
			List<FundingMilestoneView> milestones, List<PaymentOrderView> orders, @Nullable TopUpView topUp) {
	}

	/** CUS-9: one order, as the server knows it. */
	record PaymentOrderDetailView(UUID id, UUID jobId, String jobTitle, int number, String milestoneTitle, long amountVnd,
			String status, Instant createdAt, Instant expiresAt, @Nullable Instant finishedAt, String reference,
			@Nullable PaymentRefundView refund) {
	}

	record CreatePaymentOrderRequest(@NotNull Long version) {
	}

	record CreatedPaymentOrderView(UUID id, String payUrl, Instant expiresAt) {
	}

	/** N-13's help panel: VNPay's published sandbox card, from configuration. */
	record TestCardView(boolean configured, @Nullable String bank, @Nullable String number, @Nullable String holder,
			@Nullable String issued, @Nullable String otp) {
	}

	private final PaymentOrders orders;

	private final MilestoneFunding funding;

	private final VnpaySandbox sandbox;

	private final CurrentCaller callers;

	private final Refunds refunds;

	private final PaymentHistory history;

	private final JobRoles roles;

	PaymentController(PaymentOrders orders, MilestoneFunding funding, VnpaySandbox sandbox, CurrentCaller callers,
			Refunds refunds, PaymentHistory history, JobRoles roles) {
		this.roles = roles;
		this.refunds = refunds;
		this.history = history;
		this.orders = orders;
		this.funding = funding;
		this.sandbox = sandbox;
		this.callers = callers;
	}

	@GetMapping("/api/v1/jobs/{jobId}/funding")
	JobFundingView jobFunding(@PathVariable UUID jobId) {
		FundingJob job = this.funding.forCustomer(customerOf(jobId), jobId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Map<UUID, Integer> numbers = new java.util.HashMap<>();
		job.milestones().forEach(milestone -> numbers.put(milestone.milestoneId(), milestone.number()));
		UUID next = job.milestones()
			.stream()
			.filter(milestone -> "PLANNED".equals(milestone.state()))
			.map(FundingMilestone::milestoneId)
			.findFirst()
			.orElse(null);
		List<FundingMilestoneView> milestones = new ArrayList<>();
		for (FundingMilestone milestone : job.milestones()) {
			boolean fundable = false;
			String reason = null;
			if (milestone.milestoneId().equals(next)) {
				ApiException refusal = this.funding.refusal(jobId, milestone.milestoneId());
				fundable = refusal == null;
				reason = refusal == null ? null : refusal.reason().orElse(refusal.code().name());
			}
			PaymentHistory.Figures figures = this.history.figures(milestone.milestoneId());
			boolean held = Set.of("FUNDED", "REVISION_REQUESTED", "SUBMITTED").contains(milestone.state());
			long atEnd = held && milestone.priceVnd() > 0 ? Math.max(0, figures.releasableVnd() - milestone.priceVnd()) : 0;
			milestones.add(new FundingMilestoneView(milestone.milestoneId(), milestone.number(), milestone.title(),
					milestone.priceVnd(), milestone.state(), fundable, reason, figures.fundedVnd(), figures.topUpVnd(),
					figures.refundedVnd(), figures.releasedTotalVnd(), atEnd));
		}
		List<PaymentOrderView> orders = this.orders.ofJob(jobId)
			.stream()
			.map(order -> new PaymentOrderView(order.id(), order.milestoneId(), numbers.getOrDefault(order.milestoneId(), 0),
					order.amountVnd(), order.status(), order.createdAt(), order.expiresAt(), order.finishedAt(), order.txnRef(),
					refund(order.id())))
			.toList();
		TopUpView topUp = this.funding.topUpOf(jobId)
			.map(waiting -> new TopUpView(waiting.changeRequestId(), waiting.number(), waiting.amountVnd()))
			.orElse(null);
		return new JobFundingView(job.jobId(), job.title(), job.state(), job.version(), this.sandbox.missingSetting() == null,
				milestones, orders, topUp);
	}

	/** "Thanh toán thêm" (FR-36): the top-up order a waiting Change Request asks for, and its sandbox page. */
	@PostMapping("/api/v1/jobs/{jobId}/top-up-orders")
	@ResponseStatus(HttpStatus.CREATED)
	CreatedPaymentOrderView createTopUpOrder(@PathVariable UUID jobId, @Valid @RequestBody CreatePaymentOrderRequest body,
			HttpServletRequest request) {
		Created created = this.orders.createTopUp(customerOf(jobId), jobId, body.version(), request.getRemoteAddr());
		return new CreatedPaymentOrderView(created.id(), created.payUrl(), created.expiresAt());
	}

	/** "Thanh toán" (FR-41): the order, and the sandbox page the browser goes to in the same tab. */
	@PostMapping("/api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders")
	@ResponseStatus(HttpStatus.CREATED)
	CreatedPaymentOrderView createPaymentOrder(@PathVariable UUID jobId, @PathVariable UUID milestoneId,
			@Valid @RequestBody CreatePaymentOrderRequest body, HttpServletRequest request) {
		Created created = this.orders.create(customerOf(jobId), jobId, milestoneId, body.version(),
				request.getRemoteAddr());
		return new CreatedPaymentOrderView(created.id(), created.payUrl(), created.expiresAt());
	}

	@GetMapping("/api/v1/payment-orders/{paymentOrderId}")
	PaymentOrderDetailView paymentOrder(@PathVariable UUID paymentOrderId) {
		Caller caller = this.callers.get();
		Order order = this.orders.byId(paymentOrderId)
			.filter(found -> found.customerId().equals(caller.accountId()))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		customerOf(order.jobId());
		FundingJob job = this.funding.byId(order.jobId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		FundingMilestone milestone = job.milestones()
			.stream()
			.filter(found -> found.milestoneId().equals(order.milestoneId()))
			.findFirst()
			.orElse(null);
		return new PaymentOrderDetailView(order.id(), order.jobId(), job.title(), milestone == null ? 0 : milestone.number(),
				milestone == null ? "" : milestone.title(), order.amountVnd(), order.status(), order.createdAt(),
				order.expiresAt(), order.finishedAt(), order.txnRef(), refund(order.id()));
	}

	@GetMapping("/api/v1/payment/test-card")
	TestCardView testCard() {
		VnpayProperties.TestCard card = this.sandbox.testCard();
		return card == null ? new TestCardView(false, null, null, null, null, null)
				: new TestCardView(true, card.bank(), card.number(), card.holder(), card.issued(), card.otp());
	}

	private @Nullable PaymentRefundView refund(UUID orderId) {
		return this.refunds.ofOrder(orderId)
			.map(refund -> new PaymentRefundView(refund.amountVnd(), refund.status(), refund.simulated(), refund.reason().name(),
					refund.requestedAt(), refund.confirmedAt()))
			.orElse(null);
	}

	/** The Job's Customer calling, by their role in the Job (AD-43); anyone else finds nothing. */
	private UUID customerOf(UUID jobId) {
		Caller caller = this.callers.get();
		if (!this.roles.roleOf(jobId, caller).isCustomer()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return caller.requireAccountId();
	}

}
