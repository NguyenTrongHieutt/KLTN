package vn.vibefix.github.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Spike S6's token seal: a token sent to a runner workflow is encrypted with the runner's public key
 * ({@code deploy/secrets/runner-key.pub.pem}, an X.509 SubjectPublicKeyInfo PEM of at least 3072 bits)
 * and travels as one base64 dispatch input. Only the workflow holds the private key (the Actions secret
 * {@code VIBEFIX_RUNNER_KEY}) and decrypts it with {@code openssl pkeyutl}. The padding is RSA-OAEP
 * with SHA-256 and MGF1-SHA-256, named explicitly because the JDK's {@code OAEPWithSHA-256AndMGF1Padding}
 * defaults MGF1 to SHA-1, which openssl's {@code rsa_oaep_md:sha256 rsa_mgf1_md:sha256} would refuse.
 * <p>
 * The way back is {@link Opener}: the scan workflow seals its result for the probe under a second key
 * pair, so the scan's Findings never sit readable in an artifact. Not everything leaves GitHub, though:
 * each run record keeps its dispatch inputs (repository names, branches, SHAs, the sealed tokens, and
 * the result key), readable by anyone who reads the runner repository, until the author deletes the
 * runs by hand.
 */
final class RunnerSeal {

	/** The smallest modulus accepted: the author's key pairs are RSA-3072. */
	static final int MIN_BITS = 3072;

	static final String TRANSFORMATION = "RSA/ECB/OAEPPadding";

	static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
			PSource.PSpecified.DEFAULT);

	/** AES-256 then HMAC-SHA256: the two keys a scan result's envelope wraps, in that order. */
	static final int KEY_BYTES = 32;

	static final int IV_BYTES = 16;

	static final int TAG_BYTES = 32;

	private static final Pattern PEM = Pattern.compile("-----BEGIN PUBLIC KEY-----([A-Za-z0-9+/=\\s]+)-----END PUBLIC KEY-----");

	private static final Pattern PRIVATE_PEM = Pattern
		.compile("-----BEGIN (RSA )?PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END (RSA )?PRIVATE KEY-----");

	private final RSAPublicKey key;

	private RunnerSeal(RSAPublicKey key) {
		this.key = key;
	}

	/**
	 * @throws IllegalArgumentException for a file that cannot be read or holds no RSA public key of at
	 * least {@value #MIN_BITS} bits; the message never quotes the file
	 */
	static RunnerSeal read(Path file) {
		String pem;
		try {
			pem = Files.readString(file, StandardCharsets.US_ASCII);
		}
		catch (IOException | RuntimeException ex) {
			throw new IllegalArgumentException("The runner public key file cannot be read");
		}
		return of(pem);
	}

	/** @throws IllegalArgumentException as {@link #read(Path)} */
	static RunnerSeal of(String pem) {
		Matcher matcher = PEM.matcher(pem);
		if (!matcher.find()) {
			throw new IllegalArgumentException("The runner public key file holds no PEM public key (BEGIN PUBLIC KEY)");
		}
		try {
			byte[] der = Base64.getMimeDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
			if (!(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)) instanceof RSAPublicKey key)) {
				throw new IllegalArgumentException("The runner public key file holds no RSA public key");
			}
			if (key.getModulus().bitLength() < MIN_BITS) {
				throw new IllegalArgumentException("The runner public key has fewer than " + MIN_BITS + " bits");
			}
			return new RunnerSeal(key);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			if (ex instanceof IllegalArgumentException refused && refused.getMessage() != null
					&& refused.getMessage().startsWith("The runner public key")) {
				throw refused;
			}
			throw new IllegalArgumentException("The runner public key file holds no usable RSA public key");
		}
	}

	/** The value encrypted with RSA-OAEP (SHA-256, MGF1-SHA-256), as standard base64 on one line. */
	String seal(String value) {
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, this.key, OAEP);
			return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			// The exception names no value: only the algorithm or the key size.
			throw new IllegalStateException("The token could not be sealed for the runner", ex);
		}
	}

	/**
	 * Opens the envelope {@code s6-scan} makes of its {@code result.json}: {@code wrapped} is RSA-OAEP
	 * (SHA-256, MGF1-SHA-256) of a random AES-256 key followed by a random HMAC-SHA256 key, under the
	 * public half of {@code deploy/secrets/runner-result-key.pem}, which the probe sends as the dispatch
	 * input {@code result_key}; {@code ciphertext} is AES-256-CTR of the result under {@code iv}; and
	 * {@code tag} is the HMAC over the IV and the ciphertext. The tag is checked in constant time before
	 * anything is decrypted. The private key never leaves the author's machine; the public half stays in
	 * each scan run's record, among its dispatch inputs.
	 */
	static final class Opener {

		private final RSAPrivateCrtKey key;

		private final RSAPublicKey publicKey;

		private Opener(RSAPrivateCrtKey key, RSAPublicKey publicKey) {
			this.key = key;
			this.publicKey = publicKey;
		}

		/**
		 * @param file a PKCS#8 PEM ({@code BEGIN PRIVATE KEY}, what {@code openssl genpkey} writes), or PKCS#1
		 * @throws IllegalArgumentException for a file that cannot be read or holds no RSA private key of at
		 * least {@value RunnerSeal#MIN_BITS} bits; the message never quotes the file
		 */
		static Opener read(Path file) {
			String pem;
			try {
				pem = Files.readString(file, StandardCharsets.US_ASCII);
			}
			catch (IOException | RuntimeException ex) {
				throw new IllegalArgumentException("The result private key file cannot be read");
			}
			return of(pem);
		}

		/** @throws IllegalArgumentException as {@link #read(Path)} */
		static Opener of(String pem) {
			Matcher matcher = PRIVATE_PEM.matcher(pem);
			if (!matcher.find()) {
				throw new IllegalArgumentException("The result private key file holds no PEM private key (BEGIN PRIVATE KEY)");
			}
			try {
				byte[] der = Base64.getMimeDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
				byte[] pkcs8 = matcher.group(1) != null ? AppJwt.pkcs1ToPkcs8(der) : der;
				KeyFactory rsa = KeyFactory.getInstance("RSA");
				if (!(rsa.generatePrivate(new PKCS8EncodedKeySpec(pkcs8)) instanceof RSAPrivateCrtKey key)) {
					throw new IllegalArgumentException("The result private key file holds no RSA private key");
				}
				if (key.getModulus().bitLength() < MIN_BITS) {
					throw new IllegalArgumentException("The result private key has fewer than " + MIN_BITS + " bits");
				}
				RSAPublicKey publicKey = (RSAPublicKey) rsa
					.generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
				return new Opener(key, publicKey);
			}
			catch (GeneralSecurityException | IllegalArgumentException | ClassCastException ex) {
				if (ex instanceof IllegalArgumentException refused && refused.getMessage() != null
						&& refused.getMessage().startsWith("The result private key")) {
					throw refused;
				}
				throw new IllegalArgumentException("The result private key file holds no usable RSA private key");
			}
		}

		/** The public half as base64 of its X.509 DER, on one line: the dispatch input {@code result_key}. */
		String publicKey() {
			return Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
		}

		/**
		 * The plaintext, once the keys unwrap and the tag verifies.
		 * @param wrapped base64, as every other field
		 * @throws IllegalArgumentException when a field is not base64 of the right size, the keys do not
		 * unwrap, or the tag does not verify; nothing is decrypted then, and the message quotes no field
		 */
		byte[] open(String wrapped, String iv, String ciphertext, String tag) {
			byte[] ivBytes = field(iv, "iv");
			byte[] ciphertextBytes = field(ciphertext, "ciphertext");
			byte[] tagBytes = field(tag, "tag");
			if (ivBytes.length != IV_BYTES || tagBytes.length != TAG_BYTES) {
				throw new IllegalArgumentException("the envelope's iv or tag has the wrong size");
			}
			byte[] keys;
			try {
				Cipher rsa = Cipher.getInstance(TRANSFORMATION);
				rsa.init(Cipher.DECRYPT_MODE, this.key, OAEP);
				keys = rsa.doFinal(field(wrapped, "wrapped"));
			}
			catch (GeneralSecurityException ex) {
				throw new IllegalArgumentException("the envelope's keys do not unwrap with the result private key");
			}
			try {
				if (keys.length != 2 * KEY_BYTES) {
					throw new IllegalArgumentException("the envelope wraps " + keys.length + " key bytes instead of 64");
				}
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(keys, KEY_BYTES, KEY_BYTES, "HmacSHA256"));
				mac.update(ivBytes);
				mac.update(ciphertextBytes);
				if (!MessageDigest.isEqual(mac.doFinal(), tagBytes)) {
					throw new IllegalArgumentException("the envelope's tag does not verify");
				}
				Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
				aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys, 0, KEY_BYTES, "AES"), new IvParameterSpec(ivBytes));
				return aes.doFinal(ciphertextBytes);
			}
			catch (GeneralSecurityException ex) {
				throw new IllegalArgumentException("the envelope could not be decrypted");
			}
			finally {
				Arrays.fill(keys, (byte) 0);
			}
		}

		private static byte[] field(String value, String name) {
			try {
				return Base64.getDecoder().decode(value);
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("the envelope's " + name + " is not base64");
			}
		}

	}

}
