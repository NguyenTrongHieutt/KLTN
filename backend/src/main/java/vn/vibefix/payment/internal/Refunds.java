package vn.vibefix.payment.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.operations.ExternalOperations;
import vn.vibefix.operations.OperationRequest;
import vn.vibefix.shared.IdGenerator;

/**
 * Refunds (FR-43, AD-15): one irrevocable refund per FUND, never more than its remaining amount, with
 * its {@code vnp_RequestId} and gateway fixed when requested. The request writes REFUND_REQUESTED and
 * asks for the {@value RefundOperation#TYPE} External Operation in the VNPay lane; the confirmation
 * writes REFUND_CONFIRMED once, however often the operation runs.
 */
@Component
class Refunds {

	static final String REQUESTED = "payment.refund_requested";

	static final String CONFIRMED = "payment.refund_confirmed";

	/** Why money could not apply, as the Customer reads it. */
	enum Reason {
		CREW_CHANGED, ORDER_CLOSED, WRONG_AMOUNT, NOT_FUNDABLE, CANCELLED
	}

	/** One refund. */
	record Refund(UUID id, UUID fundEntryId, UUID paymentOrderId, UUID jobId, UUID milestoneId, UUID customerId, long amountVnd,
			Reason reason, String gateway, String vnpRequestId, String status, Instant requestedAt, @Nullable Instant confirmedAt) {

		boolean simulated() {
			return LedgerRecordedRefund.KEY.equals(this.gateway);
		}

	}

	private static final String COLUMNS = """
			id, fund_entry_id, payment_order_id, job_id, milestone_id, customer_account_id, amount_vnd, reason, gateway,
			vnp_request_id, status, requested_at, confirmed_at
			""";

	private final JdbcClient jdbc;

	private final ExternalOperations operations;

	private final Notifications notifications;

	private final MilestoneFunding funding;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	private final String gateway;

	Refunds(JdbcClient jdbc, ExternalOperations operations, Notifications notifications, MilestoneFunding funding,
			AuditLog auditLog, IdGenerator ids, Clock clock, @Value("${vibefix.payment.refund-gateway:ledger}") String gateway) {
		if (!"ledger".equalsIgnoreCase(gateway)) {
			throw new IllegalStateException("vibefix.payment.refund-gateway=" + gateway
					+ " is not available: VnpayRefund waits for spike S2 (Story 4.5), so refunds are ledger-recorded");
		}
		this.jdbc = jdbc;
		this.operations = operations;
		this.notifications = notifications;
		this.funding = funding;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
		this.gateway = LedgerRecordedRefund.KEY;
	}

	/**
	 * Requests the refund of an unapplied FUND, in the transaction that booked it: the refund row,
	 * REFUND_REQUESTED in the FUND's bucket, the operation, and the Customer's notice. A FUND already
	 * refunded is left alone.
	 * @return the refund, or empty when the FUND has one already or nothing remains of it
	 */
	Optional<Refund> request(UUID fundEntryId, PaymentOrders.Order order, long fundAmountVnd, Reason reason) {
		long refunded = this.jdbc.sql("SELECT coalesce(sum(amount_vnd), 0) FROM payment.refund WHERE fund_entry_id = :fund")
			.param("fund", fundEntryId)
			.query(Long.class)
			.single();
		long amount = fundAmountVnd - refunded;
		if (refunded > 0 || amount <= 0) {
			return Optional.empty();
		}
		UUID id = this.ids.newId();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO payment.refund (id, fund_entry_id, payment_order_id, job_id, product_id, milestone_id,
				    customer_account_id, amount_vnd, reason, gateway, vnp_request_id, status, requested_at)
				VALUES (:id, :fund, :order, :job, :product, :milestone, :customer, :amount, :reason, :gateway, :request,
				    'REQUESTED', :now)
				""")
			.param("id", id)
			.param("fund", fundEntryId)
			.param("order", order.id())
			.param("job", order.jobId())
			.param("product", order.productId())
			.param("milestone", order.milestoneId())
			.param("customer", order.customerId())
			.param("amount", amount)
			.param("reason", reason.name())
			.param("gateway", this.gateway)
			.param("request", id.toString().replace("-", ""))
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, refund_id, created_at)
				SELECT :id, :key, 'REFUND_REQUESTED', bucket, job_id, product_id, milestone_id, payment_order_id, :amount, :refund, :now
				FROM payment.ledger_entry WHERE id = :fund
				""")
			.param("id", this.ids.newId())
			.param("key", "REFUND_REQUESTED:" + id)
			.param("amount", amount)
			.param("refund", id)
			.param("fund", fundEntryId)
			.param("now", Timestamp.from(now))
			.update();
		this.operations.request(new OperationRequest(RefundOperation.TYPE, id, Map.of("refundId", id.toString()), null, null, 0));
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "payment.refund_requested", AuditObject.of("payment.refund", id))
			.forJob(order.jobId()));
		this.notifications.send(new NotificationRequest(REQUESTED, id, List.of(new Recipient(order.customerId(), Audience.CUSTOMER)),
				Map.of("amount", PaymentOrders.vnd(amount), "job", title(order.jobId())), "/thanh-toan/" + order.id(), order.jobId(),
				null));
		return byId(id);
	}

	/**
	 * The gateway confirmed: REFUND_CONFIRMED once, the refund {@code CONFIRMED}, the Customer told.
	 * @return whether this call confirmed it
	 */
	@Transactional
	boolean confirm(UUID refundId) {
		Refund refund = this.jdbc.sql("SELECT " + COLUMNS + " FROM payment.refund WHERE id = :id FOR UPDATE")
			.param("id", refundId)
			.query(Refunds::refund)
			.optional()
			.orElse(null);
		if (refund == null || "CONFIRMED".equals(refund.status())) {
			return false;
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("UPDATE payment.refund SET status = 'CONFIRMED', confirmed_at = :now WHERE id = :id")
			.param("id", refundId)
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, refund_id, created_at)
				SELECT :id, :key, 'REFUND_CONFIRMED', bucket, job_id, product_id, milestone_id, payment_order_id, :amount, :refund, :now
				FROM payment.ledger_entry WHERE id = :fund
				""")
			.param("id", this.ids.newId())
			.param("key", "REFUND_CONFIRMED:" + refundId)
			.param("amount", refund.amountVnd())
			.param("refund", refundId)
			.param("fund", refund.fundEntryId())
			.param("now", Timestamp.from(now))
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "payment.refund_confirmed", AuditObject.of("payment.refund", refundId))
			.forJob(refund.jobId()));
		this.notifications.send(new NotificationRequest(CONFIRMED, refundId,
				List.of(new Recipient(refund.customerId(), Audience.CUSTOMER)),
				Map.of("amount", PaymentOrders.vnd(refund.amountVnd()), "job", title(refund.jobId())),
				"/thanh-toan/" + refund.paymentOrderId(), refund.jobId(), null));
		return true;
	}

	Optional<Refund> byId(UUID refundId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payment.refund WHERE id = :id")
			.param("id", refundId)
			.query(Refunds::refund)
			.optional();
	}

	Optional<Refund> ofOrder(UUID orderId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payment.refund WHERE payment_order_id = :order ORDER BY requested_at LIMIT 1")
			.param("order", orderId)
			.query(Refunds::refund)
			.optional();
	}

	private String title(UUID jobId) {
		return this.funding.byId(jobId).map(FundingJob::title).orElse("");
	}

	private static Refund refund(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		Timestamp confirmed = rs.getTimestamp("confirmed_at");
		return new Refund(rs.getObject("id", UUID.class), rs.getObject("fund_entry_id", UUID.class),
				rs.getObject("payment_order_id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("milestone_id", UUID.class), rs.getObject("customer_account_id", UUID.class), rs.getLong("amount_vnd"),
				Reason.valueOf(rs.getString("reason")), rs.getString("gateway"), rs.getString("vnp_request_id"),
				rs.getString("status"), rs.getTimestamp("requested_at").toInstant(), confirmed == null ? null : confirmed.toInstant());
	}

}
