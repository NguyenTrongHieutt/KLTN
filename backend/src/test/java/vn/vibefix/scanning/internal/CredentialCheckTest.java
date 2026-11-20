package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.CredentialCheck;
import vn.vibefix.scanning.internal.PlantedSecrets.Planted;

/**
 * FR-66, AD-18 (Story 24.4b): {@code job} refuses an External Access identifier that looks like a credential, by the
 * credential rules alone. Personal data is never looked for, and a rule scoped to {@code .env} files by its
 * {@code paths} never applies to a value, as before the text check went.
 */
class CredentialCheckTest {

	private final CredentialCheck check = new RulesCredentialCheck();

	@Test
	void aPlantedCredentialLooksLikeOne() {
		for (String rule : List.of("github-pat", "github-app-token-stateless", "stripe-secret-key", "aws-access-key-id",
				"anthropic-api-key", "vnpay-hash-secret")) {
			Planted planted = PlantedSecrets.all().get(rule);
			assertThat(this.check.looksLikeCredential(planted.text())).as(rule).isTrue();
		}
		// The identifier field holds one bare value: a key pasted alone is refused, as AgreementReviewIT's Stripe key is.
		assertThat(this.check.looksLikeCredential(PlantedSecrets.all().get("stripe-secret-key").value())).isTrue();
		assertThat(this.check.looksLikeCredential(PlantedSecrets.all().get("github-pat").value())).isTrue();
	}

	@Test
	void personalDataAndAccountsAreNotCredentials() {
		// Twelve phone numbers: past the retired bulk threshold, which no longer exists.
		String phones = String.join(", ", java.util.stream.IntStream.range(0, 12).mapToObj(i -> "09123456" + (10 + i)).toList());
		for (String value : List.of("lan.nguyen@example.com", "hungdm.work@gmail.com", phones, "CCCD 001234567890",
				"nguyen-lan", "@vy-le-dev", "Giai đoạn 1 xong rồi chị, tối nay chị thử giúp em nhé.")) {
			assertThat(this.check.looksLikeCredential(value)).as(value).isFalse();
		}
	}

	@Test
	void aRuleScopedToEnvFilesNeverAppliesToAValue() {
		Planted dotenv = PlantedSecrets.all().get("dotenv-secret");

		assertThat(dotenv.path()).as("the rule reads .env files only").endsWith(".env.local");
		assertThat(this.check.looksLikeCredential(dotenv.text())).as("SMTP_PASSWORD=… as a value").isFalse();
		assertThat(new SecretMatcher(ScanRules.load()).find(dotenv.path(), dotenv.text()))
			.as("the same line in a .env file").hasSize(1);
	}

}
