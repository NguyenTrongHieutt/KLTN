package vn.vibefix.github.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * GitHub's webhook signatures (AD-12, SG-18): {@code X-Hub-Signature-256} is {@code sha256=} and the
 * HMAC-SHA256 of the exact body, keyed with the sending App's webhook secret. Each secret is read once
 * from its file, and the comparison takes the same time whatever it finds.
 */
@Component
class WebhookSignatures {

	private static final Logger log = LoggerFactory.getLogger(WebhookSignatures.class);

	private static final String PREFIX = "sha256=";

	private final GitHubProperties properties;

	private final Map<App, byte[]> secrets = new EnumMap<>(App.class);

	WebhookSignatures(GitHubProperties properties) {
		this.properties = properties;
	}

	/** The App a delivery names by {@code X-GitHub-Hook-Installation-Target-ID}, its App ID; empty for another. */
	Optional<App> app(@Nullable String targetId) {
		if (targetId == null) {
			return Optional.empty();
		}
		for (App app : App.values()) {
			AppProperties configured = configured(app);
			if (configured != null && configured.id() != null && targetId.equals(String.valueOf(configured.id()))) {
				return Optional.of(app);
			}
		}
		return Optional.empty();
	}

	/** Whether {@code header} is the App's signature of {@code body}; an App without a secret verifies nothing. */
	boolean verifies(App app, byte[] body, @Nullable String header) {
		if (header == null || !header.startsWith(PREFIX)) {
			return false;
		}
		byte[] secret = secret(app);
		if (secret == null) {
			return false;
		}
		byte[] given;
		try {
			given = HexFormat.of().parseHex(header.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException notHex) {
			return false;
		}
		return MessageDigest.isEqual(sign(secret, body), given);
	}

	static byte[] sign(byte[] secret, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(body);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is not available", ex);
		}
	}

	private synchronized byte @Nullable [] secret(App app) {
		byte[] secret = this.secrets.get(app);
		if (secret == null) {
			AppProperties configured = configured(app);
			if (configured == null || configured.webhookSecretFile() == null) {
				return null;
			}
			try {
				secret = Files.readString(configured.webhookSecretFile(), StandardCharsets.UTF_8).strip()
					.getBytes(StandardCharsets.UTF_8);
			}
			catch (IOException ex) {
				log.warn("The {} App's webhook secret file cannot be read, so its webhooks are refused", app);
				return null;
			}
			if (secret.length == 0) {
				log.warn("The {} App's webhook secret file is empty, so its webhooks are refused", app);
				return null;
			}
			this.secrets.put(app, secret);
		}
		return secret;
	}

	private @Nullable AppProperties configured(App app) {
		return app == App.PLATFORM ? this.properties.platformApp() : this.properties.mergeApp();
	}

}
