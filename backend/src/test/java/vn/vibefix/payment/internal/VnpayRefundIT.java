package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-15 after Spike S2 (2026-10-21): refunds through VNPay's {@code refund} call, against a local stub
 * that signs its answers as the sandbox does. A refund carries the whole amount it returns, {@code 02}
 * for the FUND's paid amount and {@code 03} for less, with the payment's own reference, transaction, and
 * creation time. A signed {@code 00} confirms it at once and the Customer hears once; an unsigned answer
 * is retried with the same request, and the later confirmation is told; a signed refusal waits for an
 * Administrator (FR-64). The hash secret is fake, built at run time.
 */
class VnpayRefundIT extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String TMN = "TESTTMN2";

	private static final String SECRET = "vnpay-refund-it-" + UUID.randomUUID();

	private static final Path SECRET_FILE = secretFile();

	/** The refund's own transaction, as the stub answers it. */
	private static final String REFUND_TRANSACTION = "15711003";

	private static final AtomicReference<String> CODE = new AtomicReference<>("00");

	private static final AtomicBoolean SIGNED = new AtomicBoolean(true);

	/** Each refund request as VNPay received it, with {@code verified} when its hash checked out. */
	private static final List<Map<String, String>> RECEIVED = Collections.synchronizedList(new ArrayList<>());

	private static final HttpServer VNPAY = start();

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private Refunds refunds;

	@Autowired
	private PaymentOrders orders;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private PlatformTransactionManager transactions;

	private UUID customer;

	private UUID product;

	private UUID job;

	@DynamicPropertySource
	static void merchant(DynamicPropertyRegistry registry) {
		registry.add("vibefix.payment.refund-gateway", () -> "vnpay");
		registry.add("vibefix.payment.vnpay.tmn-code", () -> TMN);
		registry.add("vibefix.payment.vnpay.hash-secret-file", SECRET_FILE::toString);
		registry.add("vibefix.payment.vnpay.api-url",
				() -> "http://127.0.0.1:" + VNPAY.getAddress().getPort() + "/merchant_webapi/api/transaction");
		registry.add("vibefix.payment.vnpay.allow-local-stub", () -> "true");
	}

	@AfterAll
	static void stop() {
		VNPAY.stop(0);
	}

	@BeforeEach
	void aCustomersJob() {
		CODE.set("00");
		SIGNED.set(true);
		RECEIVED.clear();
		this.customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.product = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", this.product).param("owner", this.customer)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		this.job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", this.job).param("product", this.product).param("owner", this.customer).param("now", now)
			.param("lapses", Timestamp.from(this.clock.instant().plus(Duration.ofDays(14)))).update();
	}

	@Test
	void theWholePaymentIsType02AndASignedSuccessConfirmsItAtOnce() {
		Paid paid = paid("14000099", 800000);

		UUID refund = refund(paid, 800000);
		this.worker.runDue(Lane.VNPAY);

		assertThat(status(refund)).isEqualTo("CONFIRMED");
		assertThat(RECEIVED).singleElement().satisfies(request -> {
			assertThat(request).containsEntry("verified", "true")
				.containsEntry("vnp_Command", "refund")
				.containsEntry("vnp_TmnCode", TMN)
				.containsEntry("vnp_TransactionType", "02")
				.containsEntry("vnp_Amount", "80000000")
				.containsEntry("vnp_TxnRef", paid.txnRef())
				.containsEntry("vnp_TransactionNo", "14000099")
				.containsEntry("vnp_TransactionDate", VnpaySandbox.TIME.format(paid.createdAt()))
				.containsEntry("vnp_CreateBy", VnpayRefund.CREATED_BY)
				.containsEntry("vnp_RequestId", requestId(refund));
		});
		assertThat(this.jdbc.sql("""
				SELECT vnp_transaction_no FROM payment.ledger_entry WHERE entry_type = 'REFUND_CONFIRMED' AND refund_id = :id
				""").param("id", refund).query(String.class).single()).as("the refund's own transaction")
			.isEqualTo(REFUND_TRANSACTION);
		assertThat(notifications("payment.refund_requested")).isOne();
		assertThat(notifications("payment.simulated_refund_requested")).isZero();
		assertThat(notifications("payment.refund_confirmed")).as("one notice per refund").isZero();
	}

	@Test
	void lessThanThePaymentIsType03() {
		Paid paid = paid("14000098", 800000);

		refund(paid, 300000);
		this.worker.runDue(Lane.VNPAY);

		assertThat(RECEIVED).singleElement().satisfies(request -> assertThat(request)
			.containsEntry("vnp_TransactionType", "03")
			.containsEntry("vnp_Amount", "30000000"));
	}

	@Test
	void anUnsignedAnswerIsNoAnswerAndTheLaterConfirmationIsTold() {
		Paid paid = paid("14000097", 800000);
		UUID refund = refund(paid, 800000);
		SIGNED.set(false);

		this.worker.runDue(Lane.VNPAY);

		assertThat(status(refund)).isEqualTo("REQUESTED");
		assertThat(operation(refund)).isEqualTo("PENDING VNPAY_UNSIGNED_ANSWER");
		SIGNED.set(true);
		this.clock.advance(Duration.ofMinutes(1));
		this.worker.runDue(Lane.VNPAY);

		assertThat(status(refund)).isEqualTo("CONFIRMED");
		assertThat(RECEIVED).hasSize(2)
			.extracting(request -> request.get("vnp_RequestId"))
			.as("retried with the same request")
			.containsOnly(requestId(refund));
		assertThat(notifications("payment.refund_confirmed")).isOne();
	}

	@Test
	void aSignedRefusalWaitsForAnAdministrator() {
		Paid paid = paid("14000096", 800000);
		UUID refund = refund(paid, 800000);
		CODE.set("94");

		this.worker.runDue(Lane.VNPAY);

		assertThat(status(refund)).isEqualTo("REQUESTED");
		assertThat(operation(refund)).as("failed at once, without retries").isEqualTo("FAILED VNPAY_REFUND_94");
		assertThat(RECEIVED).hasSize(1);
		await().atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	/** One paid transaction booked as an unapplied FUND, as the IPN books money that cannot apply. */
	private record Paid(UUID orderId, UUID fundId, String txnRef, Instant createdAt) {
	}

	private Paid paid(String transactionNo, long amountVnd) {
		UUID order = UUID.randomUUID();
		String txnRef = order.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
		Instant created = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose, amount_vnd,
				    agreement_version_no, txn_ref, status, created_at, expires_at, finished_at, response_code, transaction_status,
				    vnp_transaction_no)
				VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'UNAPPLIED', :created, :expires,
				    :created, '00', '00', :transaction)
				""")
			.param("id", order).param("job", this.job).param("product", this.product).param("milestone", UUID.randomUUID())
			.param("customer", this.customer).param("amount", amountVnd).param("ref", txnRef)
			.param("created", Timestamp.from(created)).param("expires", Timestamp.from(created.plus(Duration.ofMinutes(15))))
			.param("transaction", transactionNo).update();
		UUID fund = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, payment_order_id,
				    amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'UNAPPLIED', :job, :product, :order, :amount, :transaction, :created)
				""")
			.param("id", fund).param("key", "FUND:" + transactionNo).param("job", this.job).param("product", this.product)
			.param("order", order).param("amount", amountVnd).param("transaction", transactionNo)
			.param("created", Timestamp.from(created)).update();
		return new Paid(order, fund, txnRef, created);
	}

	private UUID refund(Paid paid, long amountVnd) {
		PaymentOrders.Order order = this.orders.byId(paid.orderId()).orElseThrow();
		return new TransactionTemplate(this.transactions)
			.execute(status -> this.refunds.request(paid.fundId(), order, amountVnd, Refunds.Reason.WRONG_AMOUNT).orElseThrow().id());
	}

	private String status(UUID refund) {
		return this.jdbc.sql("SELECT status FROM payment.refund WHERE id = :id").param("id", refund).query(String.class).single();
	}

	private String requestId(UUID refund) {
		return this.refunds.byId(refund).orElseThrow().vnpRequestId();
	}

	private String operation(UUID refund) {
		return this.jdbc.sql("""
				SELECT status || ' ' || coalesce(error_code, '') FROM operations.external_operation
				WHERE type = 'payment.refund' AND payload ->> 'refundId' = :id
				""").param("id", refund.toString()).query(String.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type)
			.param("id", this.customer)
			.query(Long.class)
			.single();
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/merchant_webapi/api/transaction", exchange -> {
				JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
				Map<String, String> fields = new LinkedHashMap<>();
				request.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().asString("")));
				String data = VnpaySigner.pipe(fields, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode",
						"vnp_TransactionType", "vnp_TxnRef", "vnp_Amount", "vnp_TransactionNo", "vnp_TransactionDate",
						"vnp_CreateBy", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo");
				Map<String, String> seen = new LinkedHashMap<>(fields);
				seen.put("verified", String.valueOf(VnpaySigner.matches(VnpaySigner.hmacSha512(SECRET, data),
						fields.get("vnp_SecureHash"))));
				RECEIVED.add(seen);
				Map<String, String> answer = new LinkedHashMap<>();
				answer.put("vnp_ResponseId", UUID.randomUUID().toString().replace("-", ""));
				answer.put("vnp_Command", "refund");
				answer.put("vnp_ResponseCode", CODE.get());
				answer.put("vnp_Message", "00".equals(CODE.get()) ? "Refund success" : "Too many refund count (>0)");
				answer.put("vnp_TmnCode", fields.get("vnp_TmnCode"));
				answer.put("vnp_TxnRef", fields.get("vnp_TxnRef"));
				answer.put("vnp_Amount", fields.get("vnp_Amount"));
				answer.put("vnp_BankCode", "NCB");
				answer.put("vnp_PayDate", fields.get("vnp_CreateDate"));
				answer.put("vnp_TransactionNo", REFUND_TRANSACTION);
				answer.put("vnp_TransactionType", fields.get("vnp_TransactionType"));
				answer.put("vnp_TransactionStatus", "05");
				answer.put("vnp_OrderInfo", fields.get("vnp_OrderInfo"));
				if (SIGNED.get()) {
					answer.put("vnp_SecureHash", VnpaySigner.hmacSha512(SECRET, VnpaySigner.pipe(answer, VnpaySandbox.REFUND_ANSWER)));
				}
				byte[] body = JSON.writeValueAsBytes(answer);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream stream = exchange.getResponseBody()) {
					stream.write(body);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Path secretFile() {
		try {
			Path file = Files.createTempFile("vnpay-hash-secret-", ".txt");
			Files.writeString(file, SECRET);
			file.toFile().deleteOnExit();
			return file;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
