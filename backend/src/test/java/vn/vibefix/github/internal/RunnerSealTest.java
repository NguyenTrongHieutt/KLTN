package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spike S6: a token sealed for the runner opens only with RSA-OAEP, SHA-256 and MGF1-SHA-256, the
 * parameters the workflows give {@code openssl pkeyutl}.
 */
class RunnerSealTest {

	/** Shaped like an installation token, built at run time. */
	private static final String TOKEN = "ghs_" + "s6seal" + "0123456789abcdefABCDEF0123";

	static KeyPair pair;

	private static @Nullable KeyPair resultPair;

	@TempDir
	Path directory;

	@BeforeAll
	static void aRunnerKeyPair() throws Exception {
		pair = keyPair(3072);
	}

	@Test
	void aSealedTokenOpensWithTheExplicitOaepParameters() throws Exception {
		RunnerSeal seal = RunnerSeal.read(Files.writeString(this.directory.resolve("runner-key.pub.pem"), publicPem(pair)));

		String sealed = seal.seal(TOKEN);

		assertThat(sealed).matches("[A-Za-z0-9+/]{340,2048}={0,2}").doesNotContain(TOKEN);
		Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
		cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate(), RunnerSeal.OAEP);
		assertThat(new String(cipher.doFinal(Base64.getDecoder().decode(sealed)), StandardCharsets.UTF_8)).isEqualTo(TOKEN);
		assertThat(seal.seal(TOKEN)).as("OAEP is randomized").isNotEqualTo(sealed);
	}

	@Test
	void theJdkDefaultMgf1Sha1CannotOpenIt() throws Exception {
		String sealed = RunnerSeal.read(Files.writeString(this.directory.resolve("runner-key.pub.pem"), publicPem(pair)))
			.seal(TOKEN);

		// OAEPWithSHA-256AndMGF1Padding keeps MGF1 on SHA-1 unless told otherwise.
		Cipher defaults = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
		defaults.init(Cipher.DECRYPT_MODE, pair.getPrivate());
		assertThatThrownBy(() -> defaults.doFinal(Base64.getDecoder().decode(sealed)))
			.isInstanceOf(GeneralSecurityException.class);
	}

	@Test
	void opensslOpensItAsTheWorkflowsDo() throws Exception {
		Assumptions.assumeTrue(opensslOnPath(), "openssl is not on the PATH");
		String sealed = RunnerSeal.read(Files.writeString(this.directory.resolve("runner-key.pub.pem"), publicPem(pair)))
			.seal(TOKEN);
		Path key = Files.writeString(this.directory.resolve("runner-key.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
		Path in = Files.write(this.directory.resolve("sealed.bin"), Base64.getDecoder().decode(sealed));

		Process openssl = new ProcessBuilder("openssl", "pkeyutl", "-decrypt", "-inkey", key.toString(), "-pkeyopt",
				"rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", "-pkeyopt", "rsa_mgf1_md:sha256", "-in", in.toString())
			.redirectErrorStream(true)
			.start();
		String opened = new String(openssl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(openssl.waitFor(30, TimeUnit.SECONDS)).isTrue();
		assertThat(openssl.exitValue()).as(opened).isZero();
		assertThat(opened).isEqualTo(TOKEN);
	}

	@Test
	void aBadKeyFileIsRefusedWithoutQuotingIt() throws Exception {
		Path garbage = Files.writeString(this.directory.resolve("garbage.pem"),
				"-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5IGF0IGFsbA==\n-----END PUBLIC KEY-----\n");
		Path small = Files.writeString(this.directory.resolve("small.pem"), publicPem(keyPair(2048)));
		Path privateKey = Files.writeString(this.directory.resolve("private.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

		assertThatThrownBy(() -> RunnerSeal.read(garbage)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("bm90IGEga2V5");
		assertThatThrownBy(() -> RunnerSeal.read(small)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("3072");
		assertThatThrownBy(() -> RunnerSeal.read(privateKey)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("PRIVATE KEY-----\n");
		assertThatThrownBy(() -> RunnerSeal.read(this.directory.resolve("missing.pem")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anEnvelopeOpensWithTheResultKeyAndItsPublicHalfIsTheDispatchInput() throws Exception {
		KeyPair result = resultPair();
		RunnerSeal.Opener opener = RunnerSeal.Opener
			.read(Files.writeString(this.directory.resolve("runner-result-key.pem"), pem("PRIVATE KEY", result.getPrivate().getEncoded())));
		byte[] plain = "{\"outcome\":\"SCANNED\",\"output\":{\"found\":[{\"path\":\"config/.env\"}]}}".getBytes(StandardCharsets.UTF_8);

		Map<String, String> envelope = envelope(result.getPublic(), plain);

		assertThat(opener.publicKey()).isEqualTo(Base64.getEncoder().encodeToString(result.getPublic().getEncoded()));
		assertThat(envelope).containsOnlyKeys("wrapped", "iv", "ciphertext", "tag");
		assertThat(String.join("", envelope.values())).doesNotContain("config/.env");
		assertThat(opener.open(envelope.get("wrapped"), envelope.get("iv"), envelope.get("ciphertext"), envelope.get("tag")))
			.isEqualTo(plain);
	}

	@Test
	void aChangedTagOrCiphertextOrAnotherKeyIsRefusedBeforeAnythingIsDecrypted() throws Exception {
		KeyPair result = resultPair();
		RunnerSeal.Opener opener = RunnerSeal.Opener.of(pem("PRIVATE KEY", result.getPrivate().getEncoded()));
		Map<String, String> envelope = envelope(result.getPublic(), "{\"outcome\":\"SCANNED\"}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> opener.open(envelope.get("wrapped"), envelope.get("iv"), envelope.get("ciphertext"),
				flip(envelope.get("tag")))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tag does not verify");
		assertThatThrownBy(() -> opener.open(envelope.get("wrapped"), envelope.get("iv"), flip(envelope.get("ciphertext")),
				envelope.get("tag")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("tag does not verify");
		assertThatThrownBy(() -> opener.open(envelope.get("wrapped"), flip(envelope.get("iv")), envelope.get("ciphertext"),
				envelope.get("tag")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("tag does not verify");
		Map<String, String> other = envelope(pair.getPublic(), "{}".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> opener.open(other.get("wrapped"), other.get("iv"), other.get("ciphertext"), other.get("tag")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("do not unwrap");
		assertThatThrownBy(() -> opener.open("not base64!", envelope.get("iv"), envelope.get("ciphertext"), envelope.get("tag")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("not base64!");
	}

	@Test
	void anEnvelopeMadeByTheScanWorkflowsOpensslCommandsOpens() throws Exception {
		Assumptions.assumeTrue(opensslOnPath(), "openssl is not on the PATH");
		KeyPair result = resultPair();
		RunnerSeal.Opener opener = RunnerSeal.Opener.of(pem("PRIVATE KEY", result.getPrivate().getEncoded()));
		Path plain = Files.writeString(this.directory.resolve("result.json"),
				"{\"correlation\":\"scan-0123456789abcdef\",\"outcome\":\"SCANNED\",\"step\":\"scan\",\"output\":{\"commits\":3}}");
		// s6-scan, "Write and seal the result", command by command.
		Path key = Files.write(this.directory.resolve("result-key.der"),
				Base64.getDecoder().decode(opener.publicKey()));
		Path aes = this.directory.resolve("aes.key");
		Path mac = this.directory.resolve("mac.key");
		Path iv = this.directory.resolve("iv.bin");
		openssl(null, null, "rand", "-out", aes.toString(), "32");
		openssl(null, null, "rand", "-out", mac.toString(), "32");
		openssl(null, null, "rand", "-out", iv.toString(), "16");
		String aesHex = HexFormat.of().formatHex(Files.readAllBytes(aes));
		String macHex = HexFormat.of().formatHex(Files.readAllBytes(mac));
		String ivHex = HexFormat.of().formatHex(Files.readAllBytes(iv));
		Path ciphertext = this.directory.resolve("ciphertext.bin");
		openssl(null, null, "enc", "-aes-256-ctr", "-K", aesHex, "-iv", ivHex, "-in", plain.toString(), "-out",
				ciphertext.toString());
		Path signed = Files.write(this.directory.resolve("iv-ciphertext.bin"),
				concat(Files.readAllBytes(iv), Files.readAllBytes(ciphertext)));
		Path tag = this.directory.resolve("tag.bin");
		openssl(signed, tag, "dgst", "-sha256", "-mac", "HMAC", "-macopt", "hexkey:" + macHex, "-binary");
		Path keys = Files.write(this.directory.resolve("keys.bin"), concat(Files.readAllBytes(aes), Files.readAllBytes(mac)));
		Path wrapped = this.directory.resolve("wrapped.bin");
		openssl(keys, wrapped, "pkeyutl", "-encrypt", "-pubin", "-keyform", "DER", "-inkey", key.toString(), "-pkeyopt",
				"rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha256", "-pkeyopt", "rsa_mgf1_md:sha256");

		byte[] opened = opener.open(base64(wrapped), base64(iv), base64(ciphertext), base64(tag));

		assertThat(opened).isEqualTo(Files.readAllBytes(plain));
	}

	@Test
	void aBadResultKeyFileIsRefusedWithoutQuotingIt() throws Exception {
		Path garbage = Files.writeString(this.directory.resolve("garbage-result.pem"),
				"-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5IGF0IGFsbA==\n-----END PRIVATE KEY-----\n");
		Path publicOnly = Files.writeString(this.directory.resolve("public-result.pem"), publicPem(pair));
		Path small = Files.writeString(this.directory.resolve("small-result.pem"),
				pem("PRIVATE KEY", keyPair(2048).getPrivate().getEncoded()));

		assertThatThrownBy(() -> RunnerSeal.Opener.read(garbage)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("bm90IGEga2V5");
		assertThatThrownBy(() -> RunnerSeal.Opener.read(publicOnly)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RunnerSeal.Opener.read(small)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("3072");
		assertThatThrownBy(() -> RunnerSeal.Opener.read(this.directory.resolve("missing.pem")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * What s6-scan seals, made in Java for the stand-in runner: AES-256-CTR, HMAC-SHA256 over the IV and the
	 * ciphertext, and both keys wrapped with RSA-OAEP (SHA-256, MGF1-SHA-256).
	 */
	static Map<String, String> envelope(PublicKey key, byte[] plain) throws GeneralSecurityException {
		SecureRandom random = new SecureRandom();
		byte[] keys = new byte[2 * RunnerSeal.KEY_BYTES];
		byte[] iv = new byte[RunnerSeal.IV_BYTES];
		random.nextBytes(keys);
		random.nextBytes(iv);
		Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
		aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys, 0, RunnerSeal.KEY_BYTES, "AES"), new IvParameterSpec(iv));
		byte[] ciphertext = aes.doFinal(plain);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(keys, RunnerSeal.KEY_BYTES, RunnerSeal.KEY_BYTES, "HmacSHA256"));
		mac.update(iv);
		byte[] tag = mac.doFinal(ciphertext);
		Cipher rsa = Cipher.getInstance(RunnerSeal.TRANSFORMATION);
		rsa.init(Cipher.ENCRYPT_MODE, key, RunnerSeal.OAEP);
		Base64.Encoder base64 = Base64.getEncoder();
		Map<String, String> envelope = new LinkedHashMap<>();
		envelope.put("wrapped", base64.encodeToString(rsa.doFinal(keys)));
		envelope.put("iv", base64.encodeToString(iv));
		envelope.put("ciphertext", base64.encodeToString(ciphertext));
		envelope.put("tag", base64.encodeToString(tag));
		return envelope;
	}

	private static KeyPair resultPair() throws GeneralSecurityException {
		if (resultPair == null) {
			resultPair = keyPair(3072);
		}
		return resultPair;
	}

	private static String flip(String base64) {
		byte[] bytes = Base64.getDecoder().decode(base64);
		bytes[bytes.length - 1] ^= 1;
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static String base64(Path file) throws IOException {
		return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] both = new byte[first.length + second.length];
		System.arraycopy(first, 0, both, 0, first.length);
		System.arraycopy(second, 0, both, first.length, second.length);
		return both;
	}

	/** Runs openssl as a shell pipe would: {@code in} on stdin, stdout to {@code out}. */
	private static void openssl(@Nullable Path in, @Nullable Path out, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("openssl"));
		command.addAll(List.of(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		if (in != null) {
			builder.redirectInput(in.toFile());
		}
		if (out != null) {
			builder.redirectOutput(out.toFile());
		}
		Path errors = Files.createTempFile("openssl-", ".err");
		builder.redirectError(errors.toFile());
		Process process = builder.start();
		assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
		assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
		Files.deleteIfExists(errors);
	}

	static KeyPair keyPair(int bits) throws GeneralSecurityException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(bits);
		return generator.generateKeyPair();
	}

	/** {@code BEGIN PUBLIC KEY}: what {@code openssl pkey -pubout} writes (X.509 SubjectPublicKeyInfo). */
	static String publicPem(KeyPair pair) {
		return pem("PUBLIC KEY", pair.getPublic().getEncoded());
	}

	static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
	}

	private static boolean opensslOnPath() {
		try {
			Process version = new ProcessBuilder("openssl", "version").redirectErrorStream(true).start();
			version.getInputStream().readAllBytes();
			return version.waitFor(10, TimeUnit.SECONDS) && version.exitValue() == 0;
		}
		catch (IOException ex) {
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

}
