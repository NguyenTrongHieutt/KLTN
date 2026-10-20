package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** VNPay's hash rules (API 2.1.0) and AD-14's sandbox-only start. */
class VnpaySignerTest {

	@Test
	void hmacSha512IsLowercaseHex() {
		assertThat(VnpaySigner.hmacSha512("key", "The quick brown fox jumps over the lazy dog")).isEqualTo(
				"b42af09057bac1e2d41708e48a902e09b5ff7f12ab428a4fe86653c73dd248fb82f948a549f7b791a5b41915ee4d1ec3935357e4e2317250d0372afa2ebeeb3a");
	}

	@Test
	void theQueryHashCoversTheSortedEncodedParametersWithoutTheHash() {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("vnp_TxnRef", "A1");
		params.put("vnp_OrderInfo", "Thanh toan don hang");
		params.put("vnp_Amount", "1000000");
		params.put("vnp_Empty", "");
		params.put("vnp_SecureHash", "ignored");
		params.put("vnp_SecureHashType", "ignored");

		assertThat(VnpaySigner.queryHashData(params)).isEqualTo("vnp_Amount=1000000&vnp_OrderInfo=Thanh+toan+don+hang&vnp_TxnRef=A1");
	}

	@Test
	void theApiHashJoinsTheFieldsInOrderWithAnAbsentOneEmpty() {
		assertThat(VnpaySigner.pipe(Map.of("a", "1", "c", "3"), "a", "b", "c")).isEqualTo("1||3");
	}

	@Test
	void aHashMatchesIgnoringCaseAndNeverWhenAbsent() {
		assertThat(VnpaySigner.matches("abc123", "ABC123")).isTrue();
		assertThat(VnpaySigner.matches("abc123", "abc124")).isFalse();
		assertThat(VnpaySigner.matches("abc123", null)).isFalse();
	}

	@Test
	void onlyTheSandboxHostStarts() {
		URI sandbox = URI.create("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
		URI api = URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
		SandboxGuard.check(new VnpayProperties(sandbox, api, null, null, null, false, null));

		assertThatThrownBy(() -> SandboxGuard.check(new VnpayProperties(URI.create("https://pay.vnpay.vn/vpcpay.html"), api, null,
				null, null, false, null))).isInstanceOf(IllegalStateException.class).hasMessageContaining("sandbox.vnpayment.vn");
		assertThatThrownBy(() -> SandboxGuard.check(new VnpayProperties(URI.create("http://sandbox.vnpayment.vn/x"), api, null,
				null, null, false, null))).isInstanceOf(IllegalStateException.class);
		URI stub = URI.create("http://127.0.0.1:9/api");
		assertThatThrownBy(() -> SandboxGuard.check(new VnpayProperties(sandbox, stub, null, null, null, false, null)))
			.isInstanceOf(IllegalStateException.class);
		SandboxGuard.check(new VnpayProperties(sandbox, stub, null, null, null, true, null));
	}

}
