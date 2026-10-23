package vn.vibefix.scanning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import vn.vibefix.scanning.internal.PlantedSecrets.Planted;
import vn.vibefix.scanning.internal.ScanRules.Rule;
import vn.vibefix.scanning.internal.SecretMatcher.Match;

/** AD-18, SG-13, FR-8: the rules compile with RE2/J, each finds its §B6 type, and nothing raw escapes. */
class ScanRulesTest {

	private static final ScanRules RULES = ScanRules.load();

	private static final SecretMatcher MATCHER = new SecretMatcher(RULES);

	@Test
	void everyRuleCompilesWithRe2jAndHoldsTheCredentialInAGroup() {
		assertThat(RULES.version()).isEqualTo(3);
		assertThat(RULES.rules()).hasSizeGreaterThanOrEqualTo(30)
			.allSatisfy(rule -> assertThat(rule.pattern().groupCount()).as(rule.id()).isPositive());
	}

	@Test
	void everyRuleHasAPlantedSampleAndFindsItAlone() {
		Map<String, Planted> planted = PlantedSecrets.all();
		assertThat(planted.keySet()).as("a sample for every rule")
			.containsExactlyElementsOf(RULES.rules().stream().map(Rule::id).toList());
		for (Planted sample : planted.values()) {
			List<Match> matches = MATCHER.find(sample.path(), sample.text());
			assertThat(matches).as(sample.ruleId()).hasSize(1);
			Match match = matches.getFirst();
			assertThat(match.ruleId()).as(sample.ruleId()).isEqualTo(sample.ruleId());
			assertThat(match.hash()).isEqualTo(SecretMatcher.hash(sample.ruleId(), sample.value()));
			assertThat(match.masked()).isEqualTo(SecretMatcher.mask(sample.value())).doesNotContain(sample.value());
		}
	}

	@Test
	void theSupabaseAnonKeyIsNeverFlaggedWhereverItSits() {
		String anon = PlantedSecrets.jwt("anon", 40);
		String authenticated = PlantedSecrets.jwt("authenticated", 41);

		assertThat(MATCHER.find(".env", "VITE_SUPABASE_ANON_KEY=" + anon + "\nVITE_SUPABASE_ANON_API_KEY=" + anon + "\n"))
			.isEmpty();
		assertThat(MATCHER.find("src/supabase.ts", "createClient(url, '" + anon + "')\nconst jwtSecret = '" + anon + "'\n"))
			.isEmpty();
		assertThat(MATCHER.find("src/session.ts", "const session = '" + authenticated + "'\n")).isEmpty();
		assertThat(MATCHER.find(".env", "SUPABASE_SERVICE_ROLE_KEY=" + PlantedSecrets.jwt("service_role", 42) + "\n"))
			.extracting(Match::ruleId)
			.containsExactly("supabase-service-role-key");
	}

	@Test
	void placeholdersLocalDatabasesAndPublicNamesAreNotCredentials() {
		assertThat(MATCHER.find(".env.example", """
				JWT_SECRET=your-jwt-secret
				SMTP_PASSWORD=${SMTP_PASSWORD}
				STRIPE_API_KEY=<your-stripe-key>
				ADMIN_PASSWORD=changeme
				DATABASE_URL=postgresql://postgres:postgres@localhost:5432/app
				MONGO_URL=mongodb://app:%MONGO_PASSWORD%@db.example.com/app
				SESSION_SECRET=process.env.SESSION_SECRET
				""")).isEmpty();
	}

	@Test
	void aRepeatedValueIsReportedOnceAndAnEarlierRuleClaimsItsSpan() {
		Planted stripe = PlantedSecrets.all().get("stripe-secret-key");
		String text = "STRIPE_API_KEY=" + stripe.value() + "\nSTRIPE_API_KEY_2=" + stripe.value() + "\n";

		List<Match> matches = MATCHER.find(".env", text);

		assertThat(matches).extracting(Match::ruleId).containsExactly("stripe-secret-key", "stripe-secret-key");
		assertThat(matches.stream().map(Match::hash).collect(Collectors.toSet())).hasSize(1);
	}

	@Test
	void theMaskShowsAtMostAQuarterAndNeverMoreThanFourCharactersAtEachEnd() {
		String key = "sk" + "_live_" + PlantedSecrets.alnum(24, 50);
		assertThat(SecretMatcher.mask(key)).isEqualTo(key.substring(0, 4) + SecretMatcher.BULLETS + key.substring(28));
		assertThat(SecretMatcher.mask("abcdefgh")).isEqualTo("a" + SecretMatcher.BULLETS + "h");
		assertThat(SecretMatcher.mask("short")).isEqualTo(SecretMatcher.BULLETS);
		for (int length = 1; length <= 300; length++) {
			String value = PlantedSecrets.alnum(length, length);
			int shown = SecretMatcher.mask(value).length() - SecretMatcher.BULLETS.length();
			assertThat(shown).as("length %d", length).isLessThanOrEqualTo(Math.min(8, length / 4));
		}
	}

	@Test
	void aCredentialDeepInsideAVeryLongLineIsStillFound() {
		Planted google = PlantedSecrets.all().get("gcp-api-key");
		String line = "x".repeat(10_000) + "apiKey:\"" + google.value() + "\"" + "y".repeat(10_000);

		String pieces = HistoryScanner.pieces(line, 4096);

		assertThat(pieces.lines()).allSatisfy(piece -> assertThat(piece.length()).isLessThanOrEqualTo(4096));
		List<Match> matches = MATCHER.find("dist/app.js", pieces);
		assertThat(matches).isNotEmpty().extracting(Match::ruleId).containsOnly("gcp-api-key");
		assertThat(matches).extracting(Match::hash).containsOnly(SecretMatcher.hash("gcp-api-key", google.value()));
		assertThat(HistoryScanner.pieces("short\nlines\n", 4096)).isEqualTo("short\nlines\n");
	}

	@Test
	void unsafePathsAreRecognized() {
		for (String path : Set.of("/etc/passwd", "../secret", "a/../b", "a/./b", ".git/config", "a/.GIT/hooks/x", "a//b",
				"a\\b")) {
			assertThat(HistoryScanner.safe(path)).as(path).isFalse();
		}
		assertThat(HistoryScanner.safe("src/.env")).isTrue();
		assertThat(HistoryScanner.safe(".github/workflows/ci.yml")).isTrue();
	}

	@Test
	void aBrokenRuleSetNeverLoads() {
		assertThatThrownBy(() -> ScanRules.parse(Map.of("version", 1, "placeholders", "x", "rules",
				List.of(Map.of("id", "bad", "type", "T", "pattern", "(?=lookahead)(x)")))))
			.hasMessageContaining("RE2/J");
		assertThatThrownBy(() -> ScanRules.parse(Map.of("version", 1, "placeholders", "x", "rules",
				List.of(Map.of("id", "nogroup", "type", "T", "pattern", "abc")))))
			.hasMessageContaining("capturing group");
		assertThatThrownBy(() -> ScanRules.parse(Map.of("version", 1, "placeholders", "x", "rules",
				List.of(Map.of("id", "a", "type", "T", "pattern", "(a)"), Map.of("id", "a", "type", "T", "pattern", "(b)")))))
			.hasMessageContaining("repeated");
	}

}
