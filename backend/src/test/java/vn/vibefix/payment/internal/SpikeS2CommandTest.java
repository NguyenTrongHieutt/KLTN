package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Spike S2's probe against a local stub of VNPay's {@code merchant_webapi} (Story 4.5). */
class SpikeS2CommandTest {

	private static final String SECRET = "s2-test-" + "hash-" + "material";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.UTC);

	@TempDir
	Path folder;

	private HttpServer api;

	/** The code the stub answers, and whether it signs its answer. */
	private final AtomicReference<String> answerCode = new AtomicReference<>("00");

	private final AtomicReference<Boolean> signAnswer = new AtomicReference<>(true);

	private final AtomicReference<JsonNode> received = new AtomicReference<>();

	private final AtomicReference<Boolean> requestVerified = new AtomicReference<>(false);

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	@BeforeEach
	void startStub() throws IOException {
		this.api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.api.createContext("/merchant_webapi/api/transaction", exchange -> {
			JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
			this.received.set(request);
			Map<String, String> fields = new LinkedHashMap<>();
			request.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().asString("")));
			boolean refund = "refund".equals(fields.get("vnp_Command"));
			String data = refund
					? VnpaySigner.pipe(fields, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TransactionType",
							"vnp_TxnRef", "vnp_Amount", "vnp_TransactionNo", "vnp_TransactionDate", "vnp_CreateBy", "vnp_CreateDate",
							"vnp_IpAddr", "vnp_OrderInfo")
					: VnpaySigner.pipe(fields, "vnp_RequestId", "vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_TxnRef",
							"vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo");
			this.requestVerified.set(VnpaySigner.matches(VnpaySigner.hmacSha512(SECRET, data), fields.get("vnp_SecureHash")));
			Map<String, String> answer = new LinkedHashMap<>();
			answer.put("vnp_ResponseId", "r1");
			answer.put("vnp_Command", fields.get("vnp_Command"));
			answer.put("vnp_ResponseCode", this.answerCode.get());
			answer.put("vnp_Message", "Stub");
			answer.put("vnp_TmnCode", fields.get("vnp_TmnCode"));
			answer.put("vnp_TxnRef", fields.get("vnp_TxnRef"));
			answer.put("vnp_Amount", "1000000");
			answer.put("vnp_TransactionNo", "14000001");
			answer.put("vnp_TransactionStatus", "00");
			answer.put("vnp_OrderInfo", fields.get("vnp_OrderInfo"));
			if (this.signAnswer.get()) {
				answer.put("vnp_SecureHash", VnpaySigner.hmacSha512(SECRET,
						VnpaySigner.pipe(answer, refund ? VnpaySandbox.REFUND_ANSWER : VnpaySandbox.QUERY_ANSWER)));
			}
			byte[] body = JSON.writeValueAsBytes(answer);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream stream = exchange.getResponseBody()) {
				stream.write(body);
			}
		});
		this.api.start();
	}

	@AfterEach
	void stopStub() {
		this.api.stop(0);
	}

	@Test
	void payPrintsASignedSandboxUrlWithItsReference() throws IOException {
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=pay", "--amount=10000", "--txn-ref=S2A")).isZero();

		String url = this.out.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("http")).findFirst().orElseThrow();
		Map<String, String> params = SpikeS2Command.parse(url);
		assertThat(params).containsEntry("vnp_Amount", "1000000").containsEntry("vnp_TxnRef", "S2A")
			.containsEntry("vnp_CreateDate", "20261020100000").containsEntry("vnp_ExpireDate", "20261020101500")
			.containsEntry("vnp_TmnCode", "TMN01");
		assertThat(sandbox("TMN01").verifyReturn(params).hashVerified()).as("the URL's hash covers its parameters").isTrue();
		assertThat(output()).doesNotContain(SECRET);
	}

	@Test
	void returnVerifiesTheHashAndPrintsTheCodes() throws IOException {
		Map<String, String> back = new TreeMap<>(Map.of("vnp_Amount", "1000000", "vnp_ResponseCode", "00", "vnp_TransactionNo",
				"14000001", "vnp_TransactionStatus", "00", "vnp_TxnRef", "S2A", "vnp_TmnCode", "TMN01"));
		String query = VnpaySigner.queryString(back) + "&vnp_SecureHash=" + VnpaySigner.hmacSha512(SECRET, VnpaySigner.queryHashData(back));

		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=return", "--query=http://localhost:5173/x?" + query)).isZero();
		assertThat(output()).contains("vnp_ResponseCode=00").contains("vnp_TransactionNo=14000001").contains("hash verified")
			.doesNotContain("vnp_SecureHash");

		this.out.reset();
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=return", "--query=" + query.replace("1000000", "1000"))).isOne();
		assertThat(output()).contains("hash NOT verified");
	}

	@Test
	void queryAndRefundSignTheirFieldsAndPrintTheAnswer() throws IOException {
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=query", "--txn-ref=S2A", "--transaction-date=20261020100000")).isZero();
		assertThat(this.requestVerified.get()).as("the querydr hash").isTrue();
		assertThat(this.received.get().path("vnp_Command").asString()).isEqualTo("querydr");
		assertThat(output()).contains("vnp_TransactionStatus=00").contains("hash verified");

		this.out.reset();
		this.answerCode.set("94");
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=refund", "--txn-ref=S2A", "--transaction-date=20261020100000",
				"--transaction-no=14000001", "--amount=3000", "--type=03")).as("VNPay answered, whatever its code").isZero();
		assertThat(this.requestVerified.get()).as("the refund hash").isTrue();
		assertThat(this.received.get().path("vnp_Amount").asString()).isEqualTo("300000");
		assertThat(this.received.get().path("vnp_TransactionType").asString()).isEqualTo("03");
		assertThat(output()).contains("vnp_ResponseCode=94").doesNotContain(SECRET);
	}

	@Test
	void anUnsignedAnswerOrAnUnreachableApiFails() throws IOException {
		this.signAnswer.set(false);
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=query", "--txn-ref=S2A", "--transaction-date=20261020100000")).isOne();
		assertThat(output()).contains("hash NOT verified");

		this.api.stop(0);
		this.out.reset();
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=query", "--txn-ref=S2A", "--transaction-date=20261020100000")).isOne();
		assertThat(output()).contains("VNPay did not answer");
	}

	@Test
	void aWrongInvocationAMissingSettingOrAnotherProfileIsRefused() throws IOException {
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=refund", "--txn-ref=S2A")).isEqualTo(2);
		assertThat(run(sandbox("TMN01"), "dev,cli", "--step=pay", "--amount=100")).isEqualTo(2);
		assertThat(run(sandbox(null), "dev,cli", "--step=pay", "--amount=10000")).isEqualTo(2);
		assertThat(this.err.toString(StandardCharsets.UTF_8)).contains("vibefix.payment.vnpay.tmn-code is not set");
		assertThat(run(sandbox("TMN01"), "prod,cli", "--step=pay", "--amount=10000")).isOne();
		assertThat(run(sandbox("TMN01"), "cli", "--step=pay", "--amount=10000")).isOne();
	}

	private VnpaySandbox sandbox(String tmnCode) {
		try {
			Path secret = Files.writeString(this.folder.resolve("vnpay-hash-secret"), SECRET + "\n");
			return new VnpaySandbox(new VnpayProperties(URI.create("http://127.0.0.1:9/paymentv2/vpcpay.html"),
					URI.create("http://127.0.0.1:" + this.api.getAddress().getPort() + "/merchant_webapi/api/transaction"), tmnCode,
					secret, URI.create("http://localhost:5173/thanh-toan/ket-qua"), true, null), CLOCK);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private int run(VnpaySandbox sandbox, String profiles, String... args) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles.split(","));
		environment.setProperty("vibefix.cli.command", SpikeS2Command.COMMAND);
		SpikeS2Command command = new SpikeS2Command(sandbox, environment, CLOCK, new PrintStream(this.out, true, StandardCharsets.UTF_8),
				new PrintStream(this.err, true, StandardCharsets.UTF_8));
		command.run(new DefaultApplicationArguments(args));
		return command.getExitCode();
	}

	private String output() {
		return this.out.toString(StandardCharsets.UTF_8) + this.err.toString(StandardCharsets.UTF_8);
	}

}
