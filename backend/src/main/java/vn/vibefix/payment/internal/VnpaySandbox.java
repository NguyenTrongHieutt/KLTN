package vn.vibefix.payment.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The environment's VNPay sandbox merchant (AD-14, API 2.1.0): the signed payment URL, the check of
 * its return (whose fields the IPN carries too), and the {@code merchant_webapi} calls
 * {@code querydr} and {@code refund}. Gateway times are read and written in Asia/Ho_Chi_Minh. The hash
 * secret is read from its file for each call and never returned, printed, or logged.
 */
class VnpaySandbox {

	static final String VERSION = "2.1.0";

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(VIETNAM);

	/** AD-14: an order expires after 15 minutes. */
	static final Duration EXPIRY = Duration.ofMinutes(15);

	/** The fields a {@code querydr} answer's hash covers, as VNPay's documentation lists them. */
	static final String[] QUERY_ANSWER = { "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode",
			"vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType",
			"vnp_TransactionStatus", "vnp_OrderInfo", "vnp_PromotionCode", "vnp_PromotionAmount" };

	/** The fields a {@code refund} answer's hash covers, as VNPay's documentation lists them. */
	static final String[] REFUND_ANSWER = { "vnp_ResponseId", "vnp_Command", "vnp_ResponseCode", "vnp_Message", "vnp_TmnCode",
			"vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate", "vnp_TransactionNo", "vnp_TransactionType",
			"vnp_TransactionStatus", "vnp_OrderInfo" };

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A payment page to open, with the values the later steps name. */
	record PayRequest(String url, String txnRef, String createDate, String expireDate, Instant expiresAt) {
	}

	/**
	 * What VNPay answered, without its hash.
	 * @param status the HTTP status, 0 when VNPay could not be reached
	 * @param hashVerified the answer's {@code vnp_SecureHash} matched
	 */
	record Answer(int status, Map<String, String> fields, boolean hashVerified) {
	}

	private final VnpayProperties properties;

	private final Clock clock;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	VnpaySandbox(VnpayProperties properties, Clock clock) {
		SandboxGuard.check(properties);
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * The signed payment URL for {@code amountVnd} whole VND (VNPay counts in hundredths).
	 * @throws IllegalStateException when the merchant is not configured
	 */
	PayRequest payUrl(String txnRef, long amountVnd, String orderInfo, String ipAddr, @Nullable String returnUrl) {
		Instant now = this.clock.instant();
		Map<String, String> params = new TreeMap<>();
		params.put("vnp_Version", VERSION);
		params.put("vnp_Command", "pay");
		params.put("vnp_TmnCode", tmnCode());
		params.put("vnp_Amount", Long.toString(amountVnd * 100));
		params.put("vnp_CurrCode", "VND");
		params.put("vnp_TxnRef", txnRef);
		params.put("vnp_OrderInfo", orderInfo);
		params.put("vnp_OrderType", "other");
		params.put("vnp_Locale", "vn");
		params.put("vnp_IpAddr", ipAddr);
		params.put("vnp_CreateDate", TIME.format(now));
		params.put("vnp_ExpireDate", TIME.format(now.plus(EXPIRY)));
		if (returnUrl != null) {
			params.put("vnp_ReturnUrl", returnUrl);
		}
		String hash = VnpaySigner.hmacSha512(secret(), VnpaySigner.queryHashData(params));
		String url = this.properties.payUrl() + "?" + VnpaySigner.queryString(params) + "&vnp_SecureHash=" + hash;
		return new PayRequest(url, txnRef, params.get("vnp_CreateDate"), params.get("vnp_ExpireDate"), now.plus(EXPIRY));
	}

	/** Checks a return query (the IPN sends the same fields): its hash, then its fields without the hash. */
	Answer verifyReturn(Map<String, String> query) {
		String expected = VnpaySigner.hmacSha512(secret(), VnpaySigner.queryHashData(query));
		return new Answer(200, withoutHash(query), VnpaySigner.matches(expected, query.get("vnp_SecureHash")));
	}

	/** {@code querydr}: what VNPay knows of a payment, by its reference and creation time. */
	Answer queryDr(String requestId, String txnRef, String transactionDate, String orderInfo, String ipAddr) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("vnp_RequestId", requestId);
		body.put("vnp_Version", VERSION);
		body.put("vnp_Command", "querydr");
		body.put("vnp_TmnCode", tmnCode());
		body.put("vnp_TxnRef", txnRef);
		body.put("vnp_OrderInfo", orderInfo);
		body.put("vnp_TransactionDate", transactionDate);
		body.put("vnp_CreateDate", TIME.format(this.clock.instant()));
		body.put("vnp_IpAddr", ipAddr);
		body.put("vnp_SecureHash", VnpaySigner.hmacSha512(secret(), VnpaySigner.pipe(body, "vnp_RequestId", "vnp_Version",
				"vnp_Command", "vnp_TmnCode", "vnp_TxnRef", "vnp_TransactionDate", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));
		return post(body, QUERY_ANSWER);
	}

	/**
	 * {@code refund}: {@code 02} refunds the whole payment, {@code 03} part of it.
	 * @param amountVnd whole VND to refund
	 */
	Answer refund(String requestId, String txnRef, String transactionType, long amountVnd, String transactionNo,
			String transactionDate, String createBy, String orderInfo, String ipAddr) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("vnp_RequestId", requestId);
		body.put("vnp_Version", VERSION);
		body.put("vnp_Command", "refund");
		body.put("vnp_TmnCode", tmnCode());
		body.put("vnp_TransactionType", transactionType);
		body.put("vnp_TxnRef", txnRef);
		body.put("vnp_Amount", Long.toString(amountVnd * 100));
		body.put("vnp_OrderInfo", orderInfo);
		body.put("vnp_TransactionNo", transactionNo);
		body.put("vnp_TransactionDate", transactionDate);
		body.put("vnp_CreateBy", createBy);
		body.put("vnp_CreateDate", TIME.format(this.clock.instant()));
		body.put("vnp_IpAddr", ipAddr);
		body.put("vnp_SecureHash", VnpaySigner.hmacSha512(secret(), VnpaySigner.pipe(body, "vnp_RequestId", "vnp_Version",
				"vnp_Command", "vnp_TmnCode", "vnp_TransactionType", "vnp_TxnRef", "vnp_Amount", "vnp_TransactionNo",
				"vnp_TransactionDate", "vnp_CreateBy", "vnp_CreateDate", "vnp_IpAddr", "vnp_OrderInfo")));
		return post(body, REFUND_ANSWER);
	}

	/** The setting the merchant still lacks, or {@code null} when both are set. */
	/** The merchant's terminal code, once configured. */
	@Nullable String configuredTmnCode() {
		return this.properties.tmnCode();
	}

	/** Where VNPay sends the browser back for this order: CUS-9's route, when configured. */
	@Nullable String returnUrl(String path) {
		return this.properties.returnUrl() == null ? null : this.properties.returnUrl().toString().replaceAll("/+$", "") + "/" + path;
	}

	VnpayProperties.@Nullable TestCard testCard() {
		return this.properties.testCard();
	}

	@Nullable String missingSetting() {
		if (this.properties.tmnCode() == null || this.properties.tmnCode().isBlank()) {
			return "vibefix.payment.vnpay.tmn-code";
		}
		if (this.properties.hashSecretFile() == null || !Files.isReadable(this.properties.hashSecretFile())) {
			return "vibefix.payment.vnpay.hash-secret-file";
		}
		return null;
	}

	private Answer post(Map<String, String> body, String[] answerOrder) {
		HttpRequest request = HttpRequest.newBuilder(this.properties.apiUrl())
			.timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			return new Answer(0, Map.of("error", ex.getClass().getSimpleName()), false);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Answer(0, Map.of("error", "interrupted"), false);
		}
		Map<String, String> fields = new LinkedHashMap<>();
		try {
			JsonNode answer = JSON.readTree(response.body());
			answer.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue().asString("")));
		}
		catch (RuntimeException notJson) {
			fields.put("body", response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
		}
		String expected = VnpaySigner.hmacSha512(secret(), VnpaySigner.pipe(fields, answerOrder));
		return new Answer(response.statusCode(), withoutHash(fields), VnpaySigner.matches(expected, fields.get("vnp_SecureHash")));
	}

	private String tmnCode() {
		String code = this.properties.tmnCode();
		if (code == null || code.isBlank()) {
			throw new IllegalStateException("vibefix.payment.vnpay.tmn-code is not set");
		}
		return code;
	}

	private String secret() {
		if (this.properties.hashSecretFile() == null) {
			throw new IllegalStateException("vibefix.payment.vnpay.hash-secret-file is not set");
		}
		try {
			return Files.readString(this.properties.hashSecretFile(), StandardCharsets.UTF_8).strip();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("the VNPay hash secret file cannot be read", ex);
		}
	}

	private static Map<String, String> withoutHash(Map<String, String> fields) {
		Map<String, String> shown = new TreeMap<>(fields);
		shown.remove("vnp_SecureHash");
		shown.remove("vnp_SecureHashType");
		return shown;
	}

}
