package vn.vibefix.github.internal;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * RSA keys in both PEM forms GitHub's App keys come in, for the tests, and as the signing keys of a
 * stand-in OIDC issuer (Spike S6's exchange).
 */
public final class TestKeys {

	static final KeyPair PAIR = generate();

	private TestKeys() {
	}

	/** {@code BEGIN RSA PRIVATE KEY}: what GitHub's "Generate a private key" downloads. */
	public static String pkcs1() {
		try {
			byte[] pkcs1 = PrivateKeyInfo.getInstance(PAIR.getPrivate().getEncoded()).parsePrivateKey().toASN1Primitive()
				.getEncoded();
			return pem("RSA PRIVATE KEY", pkcs1);
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** {@code BEGIN PRIVATE KEY}. */
	static String pkcs8() {
		return pem("PRIVATE KEY", PAIR.getPrivate().getEncoded());
	}

	/** {@link #PAIR} as an issuer's RS256 signing key under {@code keyId}; its public half goes in the key set. */
	static RSAKey jwk(String keyId) {
		return new RSAKey.Builder((RSAPublicKey) PAIR.getPublic()).privateKey(PAIR.getPrivate()).keyID(keyId).build();
	}

	/** Another key under the same ID: what it signs, the issuer's key set does not verify. */
	static RSAKey foreignJwk(String keyId) {
		KeyPair foreign = Foreign.PAIR;
		return new RSAKey.Builder((RSAPublicKey) foreign.getPublic()).privateKey(foreign.getPrivate()).keyID(keyId).build();
	}

	private static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
	}

	private static KeyPair generate() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Made only when a test needs it. */
	private static final class Foreign {

		static final KeyPair PAIR = generate();

	}

}
