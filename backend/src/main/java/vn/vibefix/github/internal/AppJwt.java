package vn.vibefix.github.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A GitHub App's JWT (AD-10): RS256, issued 60 seconds in the past against clock drift, valid for 9
 * minutes (GitHub accepts at most 10), with the App ID as issuer. The key is GitHub's PEM download,
 * PKCS#1 ({@code RSA PRIVATE KEY}), or PKCS#8 ({@code PRIVATE KEY}).
 */
final class AppJwt {

	static final Duration BACKDATE = Duration.ofSeconds(60);

	static final Duration LIFETIME = Duration.ofMinutes(9);

	private static final Pattern PEM = Pattern.compile("-----BEGIN (RSA )?PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END (RSA )?PRIVATE KEY-----");

	private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

	private final long appId;

	private final PrivateKey key;

	private AppJwt(long appId, PrivateKey key) {
		this.appId = appId;
		this.key = key;
	}

	/**
	 * @throws IllegalArgumentException for a PEM that holds no RSA private key; the message never
	 * quotes the key
	 */
	static AppJwt of(long appId, String pem) {
		Matcher matcher = PEM.matcher(pem);
		if (!matcher.find()) {
			throw new IllegalArgumentException("The App's key file holds no PEM RSA private key");
		}
		byte[] der = Base64.getMimeDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
		byte[] pkcs8 = matcher.group(1) != null ? pkcs1ToPkcs8(der) : der;
		try {
			return new AppJwt(appId, KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalArgumentException("The App's key file holds no usable RSA private key");
		}
	}

	String sign(Instant now) {
		String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
		String payload = encode("{\"iat\":%d,\"exp\":%d,\"iss\":\"%d\"}".formatted(now.minus(BACKDATE).getEpochSecond(),
				now.plus(LIFETIME).getEpochSecond(), this.appId));
		String unsigned = header + "." + payload;
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(this.key);
			signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
			return unsigned + "." + URL.encodeToString(signature.sign());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("The App's JWT could not be signed", ex);
		}
	}

	private static String encode(String json) {
		return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	/** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo with the rsaEncryption algorithm. */
	static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
		byte[] algorithm = { 0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
				0x01, 0x05, 0x00 };
		byte[] version = { 0x02, 0x01, 0x00 };
		byte[] octets = concat(new byte[] { 0x04 }, length(pkcs1.length), pkcs1);
		byte[] body = concat(version, algorithm, octets);
		return concat(new byte[] { 0x30 }, length(body.length), body);
	}

	private static byte[] length(int length) {
		if (length < 0x80) {
			return new byte[] { (byte) length };
		}
		if (length < 0x100) {
			return new byte[] { (byte) 0x81, (byte) length };
		}
		if (length < 0x10000) {
			return new byte[] { (byte) 0x82, (byte) (length >> 8), (byte) length };
		}
		return new byte[] { (byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length };
	}

	private static byte[] concat(byte[]... parts) {
		int size = 0;
		for (byte[] part : parts) {
			size += part.length;
		}
		byte[] all = new byte[size];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, all, offset, part.length);
			offset += part.length;
		}
		return all;
	}

}
