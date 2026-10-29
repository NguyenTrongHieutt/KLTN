package vn.vibefix.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.support.IntegrationTest;

/** FR-61 groundwork and FR-5: the seeded rule values, their bounds, and the school allowlist. */
class SettingsIT extends IntegrationTest {

	@Autowired
	private Settings settings;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void everyRuleHoldsItsPrdDefault() {
		Map<Rule, Integer> defaults = new EnumMap<>(Rule.class);
		defaults.put(Rule.AUTO_ACCEPTANCE_DAYS, 7);
		defaults.put(Rule.AUTO_ACCEPTANCE_FIRST_REMINDER_DAY, 3);
		defaults.put(Rule.AUTO_ACCEPTANCE_SECOND_REMINDER_DAY, 6);
		defaults.put(Rule.RATING_WINDOW_DAYS, 14);
		defaults.put(Rule.REVISION_ROUNDS, 2);
		defaults.put(Rule.DISPUTE_RESPONSE_DAYS, 5);
		defaults.put(Rule.TEAM_ISSUE_ANSWER_DAYS, 5);
		defaults.put(Rule.TEAM_ISSUE_ESCALATION_DAYS, 5);
		defaults.put(Rule.CLOSURE_CONFIRMATION_DAYS, 5);
		defaults.put(Rule.CANCELLATION_CONFIRMATION_DAYS, 5);
		defaults.put(Rule.AUTOMATIC_CLOSURE_DAYS, 7);
		defaults.put(Rule.FINAL_MERGE_AUTHORIZATION_DAYS, 7);
		defaults.put(Rule.AGREEMENT_STALL_DAYS, 14);
		defaults.put(Rule.STARTING_STALL_DAYS, 7);
		defaults.put(Rule.MENTOR_ACTIVE_JOB_LIMIT, 3);
		defaults.put(Rule.MENTOR_SHARE_CAP_PERCENT, 40);
		defaults.put(Rule.CREW_APPLICATION_LAPSE_DAYS, 14);

		assertThat(defaults).containsOnlyKeys(Rule.values());
		defaults.forEach((rule, value) -> assertThat(this.settings.value(rule)).as(rule.name()).isEqualTo(value));
	}

	@Test
	void theDatabaseHoldsEachRuleWithinItsBounds() {
		for (Rule rule : Rule.values()) {
			for (int outside : new int[] { rule.min() - 1, rule.max() + 1 }) {
				assertThatThrownBy(() -> this.jdbc.sql("UPDATE settings.rule_value SET value = :value WHERE rule_key = :key")
					.param("value", outside)
					.param("key", rule.name())
					.update()).as("%s = %d", rule, outside).isInstanceOf(DataIntegrityViolationException.class);
			}
		}
		// The runtime role only reads and changes the seeded rules; it cannot add one.
		assertThatThrownBy(() -> this.jdbc.sql("INSERT INTO settings.rule_value VALUES ('UNKNOWN_RULE', 1, now())").update())
			.isInstanceOf(BadSqlGrammarException.class)
			.rootCause()
			.hasMessageContaining("permission denied");
	}

	@Test
	void onlyTheDevSeedAddsSchoolDomains() throws Exception {
		assertThat(this.settings.schoolDomains()).as("integration tests load the dev seed")
			.containsExactly("hcmus.edu.vn", "hcmut.edu.vn", "hust.edu.vn", "truong.test", "vnu.edu.vn");
		String migration = java.nio.file.Files.readString(
				java.nio.file.Path.of("src/main/resources/db/migration/settings/V202610191600__settings_init.sql"));
		assertThat(migration).as("prod starts with an empty allowlist (OQ8)").doesNotContain("INSERT INTO settings.school_domain");

		this.jdbc.sql("DELETE FROM settings.school_domain").update();
		assertThat(this.settings.allowsSchoolEmail("minh@hust.edu.vn")).isFalse();
	}

	@Test
	void anAddressIsAllowedOnAnAllowlistedDomainOrASubdomainOfOne() {
		this.jdbc.sql("DELETE FROM settings.school_domain").update();
		this.jdbc.sql("INSERT INTO settings.school_domain (domain, added_at) VALUES ('hust.edu.vn', now())").update();
		this.jdbc.sql("INSERT INTO settings.school_domain (domain, added_at) VALUES ('vnu.edu.vn', now())").update();

		assertThat(this.settings.schoolDomains()).containsExactly("hust.edu.vn", "vnu.edu.vn");
		assertThat(this.settings.allowsSchoolEmail("minh@hust.edu.vn")).isTrue();
		assertThat(this.settings.allowsSchoolEmail("khoa.dd215678@sis.hust.edu.vn")).isTrue();
		assertThat(this.settings.allowsSchoolEmail("Minh@HUST.edu.VN")).isTrue();
		assertThat(this.settings.allowsSchoolEmail("minh@evilhust.edu.vn")).as("not a subdomain").isFalse();
		assertThat(this.settings.allowsSchoolEmail("minh@hust.edu.vn.evil.com")).isFalse();
		assertThat(this.settings.allowsSchoolEmail("minh@edu.vn")).isFalse();
		assertThat(this.settings.allowsSchoolEmail("hust.edu.vn")).as("no @").isFalse();
		assertThat(this.settings.allowsSchoolEmail("a@b@hust.edu.vn")).as("two @").isFalse();
		assertThat(this.settings.allowsSchoolEmail("minh@")).isFalse();
	}

	/** Story 19.4: the public school is the allowlisted domain the address matched, the most specific one. */
	@Test
	void theSchoolOfAnAddressIsItsMostSpecificAllowlistedDomain() {
		this.jdbc.sql("DELETE FROM settings.school_domain").update();
		this.jdbc.sql("INSERT INTO settings.school_domain (domain, added_at) VALUES ('hust.edu.vn', now())").update();
		this.jdbc.sql("INSERT INTO settings.school_domain (domain, added_at) VALUES ('sis.hust.edu.vn', now())").update();

		assertThat(this.settings.schoolOf("khoa@SIS.hust.edu.vn")).contains("sis.hust.edu.vn");
		assertThat(this.settings.schoolOf("khoa@a.sis.hust.edu.vn")).contains("sis.hust.edu.vn");
		assertThat(this.settings.schoolOf("minh@hust.edu.vn")).contains("hust.edu.vn");
		assertThat(this.settings.schoolOf("minh@gmail.com")).isEmpty();
		assertThat(this.settings.schoolOf("not-an-address")).isEmpty();
	}

	@Test
	void anAllowlistEntryIsALowerCaseDomain() {
		for (String bad : new String[] { "HUST.edu.vn", "hust", "-hust.edu.vn", "hust..edu.vn", "*.edu.vn" }) {
			assertThatThrownBy(() -> this.jdbc.sql("INSERT INTO settings.school_domain VALUES (:domain, now())")
				.param("domain", bad)
				.update()).as(bad).isInstanceOf(DataIntegrityViolationException.class);
		}
	}

}
