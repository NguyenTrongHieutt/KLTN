package vn.vibefix.payment.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * VNPay's {@code vnp_SecureHash} (API 2.1.0): HMAC-SHA512 with the merchant's hash secret, in
 * lowercase hex. The payment URL and its return (and the IPN) hash the parameters sorted by name as
 * {@code name=URL-encoded value} joined by {@code &}, leaving empty values out; the
 * {@code merchant_webapi} calls hash their fields joined by {@code |} in a fixed order per command.
 */
final class VnpaySigner {

	private VnpaySigner() {
	}

	static String hmacSha512(String secret, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA512");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA512 is unavailable", ex);
		}
	}

	/** The text a payment URL's hash covers: sorted, URL-encoded, without the hash fields. */
	static String queryHashData(Map<String, String> params) {
		return new TreeMap<>(params).entrySet()
			.stream()
			.filter(entry -> !entry.getKey().equals("vnp_SecureHash") && !entry.getKey().equals("vnp_SecureHashType"))
			.filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
			.map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
			.collect(Collectors.joining("&"));
	}

	/** The query string itself, the same encoding with the names encoded too. */
	static String queryString(Map<String, String> params) {
		return new TreeMap<>(params).entrySet()
			.stream()
			.filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(Collectors.joining("&"));
	}

	/** The {@code merchant_webapi} hash data: the fields in the given order, an absent one empty. */
	static String pipe(Map<String, String> fields, String... order) {
		StringBuilder data = new StringBuilder();
		for (int i = 0; i < order.length; i++) {
			if (i > 0) {
				data.append('|');
			}
			String value = fields.get(order[i]);
			data.append(value == null ? "" : value);
		}
		return data.toString();
	}

	/** A constant-time comparison of two hex hashes, ignoring case. */
	static boolean matches(String expected, @Nullable String actual) {
		if (actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
				actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
