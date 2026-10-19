package vn.vibefix.github.internal;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;

/** RSA keys in both PEM forms GitHub's App keys come in, for the tests. */
final class TestKeys {

	static final KeyPair PAIR = generate();

	private TestKeys() {
	}

	/** {@code BEGIN RSA PRIVATE KEY}: what GitHub's "Generate a private key" downloads. */
	static String pkcs1() {
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

}
