package vn.vibefix.payment.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.Administrators;
import vn.vibefix.job.MilestoneFunding;
import vn.vibefix.job.MilestoneFunding.Funded;
import vn.vibefix.job.MilestoneFunding.FundingJob;
import vn.vibefix.job.MilestoneFunding.FundingMilestone;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.notification.Todos;
import vn.vibefix.payment.internal.VnpaySandbox.Answer;
import vn.vibefix.payment.internal.VnpaySandbox.PayRequest;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Payment Orders and the IPN (FR-41, AD-14, AD-15). "Thanh toán" creates an order for one Milestone's
 * exact price at the confirmed Agreement version, voiding the Job's open one, and answers VNPay's
 * signed sandbox URL. Only the IPN changes state, classified in AD-14's order; a success is applied
 * through {@code job}'s non-throwing M1 cascade and booked as a FUND keyed by
 * {@code vnp_TransactionNo}. Money that cannot apply (a closed order, a wrong amount, a refused guard)
 * is booked in the unapplied bucket and never rejected; its refund is Story 4.7.
 */
@Component
class PaymentOrders {

	static final String FUND_TODO = "payment.fund_milestone";

	static final String RECEIVED = "payment.payment_received";

	static final String FAILED = "payment.payment_failed";

	static final String UNKNOWN = "payment.unknown_payment";

	static final String EXPIRY = "payment.order_expiry";

	/** One order as the screens and the IPN read it. */
	record Order(UUID id, UUID jobId, UUID productId, UUID milestoneId, UUID customerId, long amountVnd, String txnRef,
			String status, Instant createdAt, Instant expiresAt, @Nullable Instant finishedAt) {
	}

	/** A new order, with the sandbox page to open. */
	record Created(UUID id, String payUrl, Instant expiresAt) {
	}

	/** The IPN's answer to VNPay (AD-14). */
	record IpnAnswer(String rspCode, String message) {

		static final IpnAnswer CONFIRMED = new IpnAnswer("00", "Confirm Success");

		static final IpnAnswer UNKNOWN_ORDER = new IpnAnswer("01", "Order not Found");

		static final IpnAnswer REPLAYED = new IpnAnswer("02", "Order already confirmed");

		static final IpnAnswer BAD_CHECKSUM = new IpnAnswer("97", "Invalid Checksum");

	}

	private static final String COLUMNS = """
			id, job_id, product_id, milestone_id, customer_account_id, amount_vnd, txn_ref, status, created_at, expires_at,
			finished_at
			""";

	private final JdbcClient jdbc;

	private final MilestoneFunding funding;

	private final VnpaySandbox sandbox;

	private final Deadlines deadlines;

	private final Notifications notifications;

	private final Todos todos;

	private final Administrators administrators;

	private final AuditLog auditLog;

	private final IdGenerator ids;

	private final Clock clock;

	PaymentOrders(JdbcClient jdbc, MilestoneFunding funding, VnpaySandbox sandbox, Deadlines deadlines,
			Notifications notifications, Todos todos, Administrators administrators, AuditLog auditLog, IdGenerator ids,
			Clock clock) {
		this.jdbc = jdbc;
		this.funding = funding;
		this.sandbox = sandbox;
		this.deadlines = deadlines;
		this.notifications = notifications;
		this.todos = todos;
		this.administrators = administrators;
		this.auditLog = auditLog;
		this.ids = ids;
		this.clock = clock;
	}

	/**
	 * "Thanh toán" (FR-41): an order for the Milestone's exact price, at the {@code Job.version} the
	 * screen loaded; the Job's open order, if any, is voided first.
	 * @throws ApiException {@code NOT_FOUND}; {@code STALE}; {@code CONFLICT JOB_FROZEN};
	 * {@code GUARD_FAILED} with {@code job}'s refusal or {@code PAYMENT_NOT_CONFIGURED}
	 */
	@Transactional
	Created create(UUID customerId, UUID jobId, UUID milestoneId, long version, String ipAddr) {
		FundingJob job = this.funding.forCustomer(customerId, jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		FundingMilestone milestone = job.milestones()
			.stream()
			.filter(found -> found.milestoneId().equals(milestoneId))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (job.version() != version) {
			throw new ApiException(ErrorCode.STALE);
		}
		ApiException refusal = this.funding.refusal(jobId, milestoneId);
		if (refusal != null) {
			throw refusal;
		}
		if (this.sandbox.missingSetting() != null) {
			throw new ApiException(ErrorCode.GUARD_FAILED, PaymentReason.PAYMENT_NOT_CONFIGURED);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("UPDATE payment.payment_order SET status = 'VOIDED', finished_at = :now WHERE job_id = :job AND status = 'OPEN'")
			.param("job", jobId)
			.param("now", Timestamp.from(now))
			.update();
		ensureAccount(milestoneId, jobId, job.productId(), now);
		UUID id = this.ids.newId();
		String txnRef = id.toString().replace("-", "").toUpperCase(Locale.ROOT);
		PayRequest pay = this.sandbox.payUrl(txnRef, milestone.priceVnd(), "Thanh toan Giai doan " + milestone.number()
				+ " vibefix " + txnRef.substring(0, 8), ipAddr, this.sandbox.returnUrl(id.toString()));
		try {
			this.jdbc.sql("""
					INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose, amount_vnd,
					    agreement_version_no, txn_ref, status, created_at, expires_at)
					VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, :version, :ref, 'OPEN', :now, :expires)
					""")
				.param("id", id)
				.param("job", jobId)
				.param("product", job.productId())
				.param("milestone", milestoneId)
				.param("customer", customerId)
				.param("amount", milestone.priceVnd())
				.param("version", job.agreementVersionNo())
				.param("ref", txnRef)
				.param("now", Timestamp.from(now))
				.param("expires", Timestamp.from(pay.expiresAt()))
				.update();
		}
		catch (DuplicateKeyException concurrent) {
			throw new ApiException(ErrorCode.STALE);
		}
		this.deadlines.schedule(new DeadlineRequest(EXPIRY, id, "OPEN", jobId, pay.expiresAt()));
		this.auditLog.record(AuditEntry.of(AuditActor.user(customerId), "payment.order_created",
				AuditObject.of("payment.payment_order", id)).forJob(jobId));
		return new Created(id, pay.url(), pay.expiresAt());
	}

	/**
	 * The IPN (AD-14), classified in order: a bad hash, a foreign terminal, or a currency other than
	 * VND answers {@code 97} and records nothing; an unknown reference {@code 01} and alerts; a
	 * transaction number already booked {@code 02}; a failure marks the open order failed; a success is
	 * applied or booked unapplied. Both answer {@code 00}.
	 */
	@Transactional
	IpnAnswer ipn(Map<String, String> params) {
		if (this.sandbox.missingSetting() != null) {
			return IpnAnswer.BAD_CHECKSUM;
		}
		Answer verified = this.sandbox.verifyReturn(params);
		String currency = params.get("vnp_CurrCode");
		if (!verified.hashVerified() || !String.valueOf(this.sandbox.configuredTmnCode()).equals(params.get("vnp_TmnCode"))
				|| (currency != null && !"VND".equals(currency))) {
			return IpnAnswer.BAD_CHECKSUM;
		}
		String txnRef = String.valueOf(params.get("vnp_TxnRef"));
		Order order = byReference(txnRef).orElse(null);
		String transactionNo = params.get("vnp_TransactionNo");
		if (order == null) {
			this.notifications.send(new NotificationRequest(UNKNOWN, cause("unknown:" + txnRef + ":" + transactionNo),
					this.administrators.activeIds().stream().map(admin -> new Recipient(admin, Audience.ADMIN)).toList(), Map.of(),
					"/quan-tri", null, null));
			return IpnAnswer.UNKNOWN_ORDER;
		}
		if (transactionNo != null && funded(transactionNo)) {
			return IpnAnswer.REPLAYED;
		}
		Instant now = this.clock.instant();
		String responseCode = params.get("vnp_ResponseCode");
		String transactionStatus = params.get("vnp_TransactionStatus");
		if (!"00".equals(responseCode) || !"00".equals(transactionStatus) || transactionNo == null) {
			int failed = this.jdbc.sql("""
					UPDATE payment.payment_order SET status = 'FAILED', finished_at = :now, response_code = :code,
					    transaction_status = :status WHERE id = :id AND status = 'OPEN'
					""")
				.param("id", order.id())
				.param("now", Timestamp.from(now))
				.param("code", responseCode)
				.param("status", transactionStatus)
				.update();
			if (failed == 1) {
				notifyFailed(order);
			}
			return IpnAnswer.CONFIRMED;
		}
		long amount = parseAmount(params.get("vnp_Amount"));
		boolean applies = "OPEN".equals(order.status()) && amount == order.amountVnd();
		Funded funded = applies ? this.funding.tryFund(order.jobId(), order.milestoneId(), order.id()).orElse(null) : null;
		lockAccount(order.milestoneId(), order.jobId(), order.productId(), now);
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', :bucket, :job, :product, :milestone, :order, :amount, :no, :now)
				""")
			.param("id", this.ids.newId())
			.param("key", "FUND:" + transactionNo)
			.param("bucket", funded != null ? "MILESTONE" : "UNAPPLIED")
			.param("job", order.jobId())
			.param("product", order.productId())
			.param("milestone", order.milestoneId())
			.param("order", order.id())
			.param("amount", amount)
			.param("no", transactionNo)
			.param("now", Timestamp.from(now))
			.update();
		this.jdbc.sql("""
				UPDATE payment.payment_order SET status = :status, finished_at = :now, response_code = :code,
				    transaction_status = :tstatus, vnp_transaction_no = :no WHERE id = :id
				""")
			.param("id", order.id())
			.param("status", funded != null ? "PAID" : "UNAPPLIED")
			.param("now", Timestamp.from(now))
			.param("code", responseCode)
			.param("tstatus", transactionStatus)
			.param("no", transactionNo)
			.update();
		this.auditLog.record(AuditEntry.of(AuditActor.system(), funded != null ? "payment.fund_applied" : "payment.fund_unapplied",
				AuditObject.of("payment.payment_order", order.id())).forJob(order.jobId()));
		if (funded != null) {
			this.todos.closeAll(FUND_TODO, order.milestoneId());
			String title = this.funding.byId(order.jobId()).map(FundingJob::title).orElse("");
			this.notifications.send(new NotificationRequest(RECEIVED, order.id(),
					List.of(new Recipient(order.customerId(), Audience.CUSTOMER)),
					Map.of("job", title, "amount", vnd(amount), "n", String.valueOf(funded.number())),
					"/thanh-toan/" + order.id(), order.jobId(), null));
		}
		return IpnAnswer.CONFIRMED;
	}

	/** {@value #EXPIRY}: an order still open after 15 minutes expires (AD-14); S2 decides on {@code querydr}. */
	@Transactional
	boolean expire(UUID orderId) {
		Order order = byId(orderId).orElse(null);
		if (order == null || !"OPEN".equals(order.status())) {
			return false;
		}
		this.jdbc.sql("UPDATE payment.payment_order SET status = 'EXPIRED', finished_at = :now WHERE id = :id AND status = 'OPEN'")
			.param("id", orderId)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		notifyFailed(order);
		return true;
	}

	Optional<Order> byId(UUID orderId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payment.payment_order WHERE id = :id")
			.param("id", orderId)
			.query(PaymentOrders::order)
			.optional();
	}

	List<Order> ofJob(UUID jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payment.payment_order WHERE job_id = :job ORDER BY created_at DESC, id")
			.param("job", jobId)
			.query(PaymentOrders::order)
			.list();
	}

	/** Each Milestone of a confirmed Agreement has its ledger account, created once (AD-15). */
	void ensureAccount(UUID milestoneId, UUID jobId, UUID productId, Instant now) {
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at)
				VALUES (:milestone, :job, :product, :now) ON CONFLICT (milestone_id) DO NOTHING
				""")
			.param("milestone", milestoneId)
			.param("job", jobId)
			.param("product", productId)
			.param("now", Timestamp.from(now))
			.update();
	}

	private Optional<Order> byReference(String txnRef) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM payment.payment_order WHERE txn_ref = :ref")
			.param("ref", txnRef)
			.query(PaymentOrders::order)
			.optional();
	}

	/** AD-4's lock order: after the Job row ({@code tryFund}), the Milestone's ledger account. */
	private void lockAccount(UUID milestoneId, UUID jobId, UUID productId, Instant now) {
		ensureAccount(milestoneId, jobId, productId, now);
		this.jdbc.sql("SELECT milestone_id FROM payment.ledger_account WHERE milestone_id = :id FOR UPDATE")
			.param("id", milestoneId)
			.query(UUID.class)
			.single();
	}

	private boolean funded(String transactionNo) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment.ledger_entry WHERE entry_type = 'FUND' AND vnp_transaction_no = :no)")
			.param("no", transactionNo)
			.query(Boolean.class)
			.single();
	}

	private void notifyFailed(Order order) {
		FundingJob job = this.funding.byId(order.jobId()).orElse(null);
		int number = job == null ? 1 : job.milestones()
			.stream()
			.filter(milestone -> milestone.milestoneId().equals(order.milestoneId()))
			.mapToInt(FundingMilestone::number)
			.findFirst()
			.orElse(1);
		this.notifications.send(new NotificationRequest(FAILED, cause("failed:" + order.id()),
				List.of(new Recipient(order.customerId(), Audience.CUSTOMER)),
				Map.of("job", job == null ? "" : job.title(), "n", String.valueOf(number)), "/thanh-toan/" + order.id(),
				order.jobId(), null));
	}

	private static long parseAmount(@Nullable String hundredths) {
		try {
			return hundredths == null ? -1 : Long.parseLong(hundredths) / 100;
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private static UUID cause(String fact) {
		return UUID.nameUUIDFromBytes(fact.getBytes(StandardCharsets.UTF_8));
	}

	/** "2.000.000 ₫", as UX-DR20 writes whole VND. */
	static String vnd(long amount) {
		return String.format(Locale.ROOT, "%,d", amount).replace(',', '.') + " ₫";
	}

	private static Order order(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		Timestamp finished = rs.getTimestamp("finished_at");
		return new Order(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
				rs.getObject("product_id", UUID.class), rs.getObject("milestone_id", UUID.class),
				rs.getObject("customer_account_id", UUID.class), rs.getLong("amount_vnd"), rs.getString("txn_ref"),
				rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
				finished == null ? null : finished.toInstant());
	}

}
