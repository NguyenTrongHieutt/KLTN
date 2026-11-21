package vn.vibefix.github.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.internal.GitHubProperties.AppProperties;

/**
 * AD-12, SG-18: a delivery names its App by App ID and is signed with that App's own secret. Since Story 24.5a both
 * Apps have a webhook, each with a secret of its own, so the other App's secret never verifies.
 */
class WebhookSignaturesTest {

	private static final byte[] BODY = "{\"action\":\"opened\",\"note\":\"Quán Mây\"}".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path directory;

	private byte[] platformSecret;

	private byte[] sourceSecret;

	/** A secret no App of this environment has, as another sender's would be. */
	private byte[] otherSecret;

	private WebhookSignatures signatures;

	@BeforeEach
	void bothAppsWithTheirOwnSecrets() throws IOException {
		// Fake secrets, built at run time; the file's trailing newline is not part of the secret.
		this.platformSecret = ("platform-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
		this.sourceSecret = ("source-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
		this.otherSecret = ("other-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
		Path platform = Files.writeString(this.directory.resolve("platform-webhook-secret"),
				new String(this.platformSecret, StandardCharsets.UTF_8) + "\n");
		Path source = Files.writeString(this.directory.resolve("source-webhook-secret"),
				new String(this.sourceSecret, StandardCharsets.UTF_8) + "\n");
		this.signatures = new WebhookSignatures(new GitHubProperties(URI.create("https://api.github.com"), null,
				"vibefix-dev", new AppProperties(123456L, 7L, null, platform), null, null, null,
				new AppProperties(2222L, null, null, source)));
	}

	@Test
	void aDeliveryNamesItsAppByAppId() {
		assertThat(this.signatures.app("123456")).contains(App.PLATFORM);
		assertThat(this.signatures.app("2222")).contains(App.SOURCE);
		assertThat(this.signatures.app("999")).as("an unknown App ID stays refused").isEmpty();
		assertThat(this.signatures.app(null)).isEmpty();
	}

	@Test
	void onlyTheAppsOwnSignatureOfTheExactBodyVerifies() {
		String platform = header(this.platformSecret, BODY);
		String source = header(this.sourceSecret, BODY);

		assertThat(this.signatures.verifies(App.PLATFORM, BODY, platform)).isTrue();
		assertThat(this.signatures.verifies(App.SOURCE, BODY, source)).isTrue();
		assertThat(this.signatures.verifies(App.PLATFORM, BODY, header(this.otherSecret, BODY)))
			.as("another sender's secret")
			.isFalse();
		assertThat(this.signatures.verifies(App.SOURCE, BODY, platform)).as("the Platform App's secret").isFalse();
		assertThat(this.signatures.verifies(App.PLATFORM, BODY, source)).as("the Source App's secret").isFalse();
		byte[] changed = BODY.clone();
		changed[2]++;
		assertThat(this.signatures.verifies(App.PLATFORM, changed, platform)).as("one byte changed").isFalse();
		assertThat(this.signatures.verifies(App.SOURCE, changed, source)).as("one byte changed").isFalse();
	}

	@Test
	void aMissingOrMalformedSignatureNeverVerifies() {
		String hex = HexFormat.of().formatHex(WebhookSignatures.sign(this.platformSecret, BODY));

		assertThat(this.signatures.verifies(App.PLATFORM, BODY, null)).isFalse();
		assertThat(this.signatures.verifies(App.PLATFORM, BODY, hex)).as("no sha256= prefix").isFalse();
		assertThat(this.signatures.verifies(App.PLATFORM, BODY, "sha1=" + hex)).isFalse();
		assertThat(this.signatures.verifies(App.PLATFORM, BODY, "sha256=zz" + hex.substring(2))).isFalse();
		assertThat(this.signatures.verifies(App.PLATFORM, BODY, "sha256=" + hex.substring(2))).as("too short").isFalse();
	}

	@Test
	void anAppWithoutASecretVerifiesNothing() {
		WebhookSignatures none = new WebhookSignatures(new GitHubProperties(URI.create("https://api.github.com"), null,
				"vibefix-dev", new AppProperties(123456L, 7L, null), null, null, null, new AppProperties(2222L, null, null)));

		assertThat(none.app("123456")).contains(App.PLATFORM);
		assertThat(none.app("2222")).contains(App.SOURCE);
		assertThat(none.verifies(App.PLATFORM, BODY, header(this.platformSecret, BODY))).isFalse();
		assertThat(none.verifies(App.SOURCE, BODY, header(this.sourceSecret, BODY))).isFalse();
	}

	static String header(byte[] secret, byte[] body) {
		return "sha256=" + HexFormat.of().formatHex(WebhookSignatures.sign(secret, body));
	}

}
