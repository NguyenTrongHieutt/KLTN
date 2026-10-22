package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.payment.Ledger;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.Role;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 14.2 (FR-36, FR-43, AD-14, AD-15, NFR-4): a Change Request that raises the Held Milestone's price
 * waits for the Customer's top-up, whose IPN applies it and books the money as a FUND in the Change Request
 * bucket with a TOPUP_APPLIED; a cut takes effect at once and its difference is refunded with the release;
 * a top-up paid after its request lapsed is refunded.
 */
class TopUpIT extends IntegrationTest {

	private static final String TMN = "TOPUPTMN";

	private static final String SECRET = "top-up-it-" + UUID.randomUUID();

	private static final Path SECRET_FILE = secretFile();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private Releases releases;

	@Autowired
	private Ledger ledger;

	@Autowired
	private JdbcLedger entries;

	private HeldCrewJob world;

	@DynamicPropertySource
	static void merchant(DynamicPropertyRegistry registry) {
		registry.add("vibefix.payment.vnpay.tmn-code", () -> TMN);
		registry.add("vibefix.payment.vnpay.hash-secret-file", SECRET_FILE::toString);
		registry.add("vibefix.payment.vnpay.return-url", () -> "http://localhost:5173/thanh-toan");
	}

	@BeforeEach
	void aHeldMilestoneOfOneMillion() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	@Test
	void aRaiseWaitsForTheTopUpAndItsPaymentAppliesIt() throws Exception {
		agree(1_200_000);
		assertThat(text("SELECT status FROM job.change_request WHERE job_id = '" + this.world.job() + "'")).isEqualTo("AWAITING_TOP_UP");
		assertThat(count("SELECT top_up_vnd FROM job.change_request WHERE job_id = '" + this.world.job() + "'")).isEqualTo(200_000);
		assertThat(count("SELECT price_vnd FROM job.milestone WHERE id = '" + this.world.milestone() + "'"))
			.as("not before the top-up").isEqualTo(HeldCrewJob.PRICE);
		assertThat(openTodos("payment.fund_top_up")).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.change_request_awaiting_top_up'"))
			.isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/funding").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.topUp.amountVnd").value(200_000));

		UUID order = topUpOrder();
		ipn(paid(txnRef(order), "TU-" + order.toString().substring(0, 8), 200_000));

		assertThat(text("SELECT status FROM payment.payment_order WHERE id = '" + order + "'")).isEqualTo("PAID");
		assertThat(text("SELECT status FROM job.change_request WHERE job_id = '" + this.world.job() + "'")).isEqualTo("APPLIED");
		assertThat(count("SELECT price_vnd FROM job.milestone WHERE id = '" + this.world.milestone() + "'")).isEqualTo(1_200_000);
		assertThat(count("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'FUND' AND bucket = 'CHANGE_REQUEST'"))
			.isEqualTo(200_000);
		assertThat(count("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'TOPUP_APPLIED'")).isEqualTo(200_000);
		assertThat(this.ledger.releasable(this.world.milestone())).isEqualTo(1_200_000);
		assertThat(openTodos("payment.fund_top_up")).isZero();

		release();
		assertThat(count("SELECT coalesce(sum(amount_vnd), 0) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'"))
			.isEqualTo(1_200_000);
		assertThat(LedgerMath.balanced(this.entries.entries(this.world.milestone()))).isTrue();
	}

	@Test
	void aCutTakesEffectAtOnceAndItsDifferenceIsRefundedWithTheRelease() throws Exception {
		agree(600_000);
		assertThat(text("SELECT status FROM job.change_request WHERE job_id = '" + this.world.job() + "'")).isEqualTo("APPLIED");
		assertThat(count("SELECT price_vnd FROM job.milestone WHERE id = '" + this.world.milestone() + "'")).isEqualTo(600_000);
		this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/funding").with(as(this.world.lan(), Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.milestones[0].refundAtEndVnd").value(400_000));
		assertThat(count("SELECT count(*) FROM payment.refund")).as("nothing is refunded before the end").isZero();

		release();
		assertThat(count("SELECT coalesce(sum(amount_vnd), 0) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'"))
			.isEqualTo(600_000);
		assertThat(this.jdbc.sql("SELECT reason || ' ' || amount_vnd FROM payment.refund").query(String.class).list())
			.containsExactly("PRICE_CUT 400000");
		assertThat(this.ledger.releasable(this.world.milestone())).isZero();
		assertThat(LedgerMath.balanced(this.entries.entries(this.world.milestone()))).isTrue();
	}

	@Test
	void aTopUpPaidAfterItsRequestLapsedIsBookedUnappliedAndRefunded() throws Exception {
		agree(1_200_000);
		UUID order = topUpOrder();
		this.jdbc.sql("UPDATE job.change_request SET status = 'LAPSED', closed_at = now() WHERE job_id = :job")
			.param("job", this.world.job()).update();

		ipn(paid(txnRef(order), "TU-" + order.toString().substring(0, 8), 200_000));

		assertThat(text("SELECT status FROM payment.payment_order WHERE id = '" + order + "'")).isEqualTo("UNAPPLIED");
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'FUND' AND bucket = 'UNAPPLIED'")).isOne();
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'TOPUP_APPLIED'")).isZero();
		assertThat(this.jdbc.sql("SELECT reason || ' ' || amount_vnd FROM payment.refund").query(String.class).list())
			.containsExactly("NOT_FUNDABLE 200000");
		assertThat(this.ledger.releasable(this.world.milestone())).isEqualTo(HeldCrewJob.PRICE);
	}

	/** The Mentor proposes the new price with its impact, the Customer approves, and the Mentor confirms. */
	private void agree(long price) throws Exception {
		this.mvc.perform(post("/api/v1/boards/" + this.world.job() + "/change-requests").with(as(this.world.hung(), Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"impact\":\"Đổi giá giai đoạn 1\",\"items\":[],\"version\":" + version() + ",\"milestones\":[{\"milestoneId\":\""
					+ this.world.milestone() + "\",\"priceVnd\":" + price + ",\"remove\":false}]}"))
			.andExpect(status().isCreated());
		String request = text("SELECT id::text FROM job.change_request WHERE job_id = '" + this.world.job() + "'");
		command(this.world.lan(), Role.CUSTOMER, request, "approve");
		command(this.world.hung(), Role.MENTOR, request, "confirm");
	}

	private void command(UUID account, Role role, String request, String action) throws Exception {
		this.mvc.perform(post("/api/v1/change-requests/" + request + "/" + action).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version() + "}"))
			.andExpect(status().isOk());
	}

	private UUID topUpOrder() throws Exception {
		String body = this.mvc.perform(post("/api/v1/jobs/" + this.world.job() + "/top-up-orders")
			.with(as(this.world.lan(), Role.CUSTOMER))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + version() + "}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private void release() {
		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
	}

	private static Map<String, String> paid(String txnRef, String transactionNo, long amountVnd) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("vnp_Amount", Long.toString(amountVnd * 100));
		fields.put("vnp_BankCode", "NCB");
		fields.put("vnp_OrderInfo", "Thanh toan them Giai doan 1");
		fields.put("vnp_PayDate", "20261023100500");
		fields.put("vnp_ResponseCode", "00");
		fields.put("vnp_TmnCode", TMN);
		fields.put("vnp_TransactionNo", transactionNo);
		fields.put("vnp_TransactionStatus", "00");
		fields.put("vnp_TxnRef", txnRef);
		return fields;
	}

	private void ipn(Map<String, String> fields) throws Exception {
		MockHttpServletRequestBuilder request = get("/webhooks/vnpay/ipn");
		fields.forEach(request::param);
		request.param("vnp_SecureHash", VnpaySigner.hmacSha512(SECRET, VnpaySigner.queryHashData(fields)));
		this.mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
	}

	private String txnRef(UUID order) {
		return text("SELECT txn_ref FROM payment.payment_order WHERE id = '" + order + "'");
	}

	private long version() {
		return count("SELECT version FROM job.job WHERE id = '" + this.world.job() + "'");
	}

	private long openTodos(String key) {
		return count("SELECT count(*) FROM notification.todo WHERE todo_key = '" + key + "' AND account_id = '"
				+ this.world.lan() + "' AND closed_at IS NULL");
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
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
