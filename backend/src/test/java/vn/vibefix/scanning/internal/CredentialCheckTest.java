package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.CredentialCheck;
import vn.vibefix.scanning.internal.PlantedSecrets.Planted;
import vn.vibefix.scanning.internal.ScanRules.Rule;

/**
 * FR-66, AD-18 (Story 24.4b): {@code job} refuses an External Access identifier that looks like a credential, by the
 * credential rules alone. Personal data is never looked for, and a rule scoped to some files by its {@code paths}
 * never applies to a value, whatever files it names.
 */
class CredentialCheckTest {

	private static final ScanRules RULES = ScanRules.load();

	private final CredentialCheck check = new RulesCredentialCheck();

	@Test
	void everyRuleWithNoPathsFindsItsPlantedSample() {
		List<Rule> anywhere = RULES.rules().stream().filter(rule -> rule.paths() == null).toList();

		assertThat(anywhere).as("the rules a value is checked with").hasSizeGreaterThanOrEqualTo(30);
		for (Rule rule : anywhere) {
			Planted planted = PlantedSecrets.all().get(rule.id());
			assertThat(this.check.looksLikeCredential(planted.text())).as(rule.id()).isTrue();
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
	void noRuleWithPathsEverAppliesToAValue() {
		List<Rule> scoped = RULES.rules().stream().filter(rule -> rule.paths() != null).toList();

		assertThat(scoped).as("the rules scoped to some files").isNotEmpty();
		for (Rule rule : scoped) {
			Planted planted = PlantedSecrets.all().get(rule.id());
			assertThat(new SecretMatcher(RULES).find(planted.path(), planted.text())).as(rule.id() + " in its own file")
				.isNotEmpty();
			assertThat(this.check.looksLikeCredential(planted.text())).as(rule.id() + " as a value").isFalse();
		}
		// Whatever files a rule names, a .txt file included, its paths keep it off every value.
		CredentialCheck text = new RulesCredentialCheck(ScanRules.parse(Map.of("version", 1, "placeholders", "none",
				"rules", List.of(Map.of("id", "txt-only", "type", "T", "pattern", "(abc)", "paths", "\\.txt$"),
						Map.of("id", "anywhere", "type", "T", "pattern", "(xyz)")))));
		assertThat(text.looksLikeCredential("abc")).isFalse();
		assertThat(text.looksLikeCredential("xyz")).isTrue();
	}

}
