package vn.vibefix.settings.internal;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;

/** {@link Settings} from the {@code settings} schema; small tables, so every call reads them. */
@Component
class JdbcSettings implements Settings {

	private final JdbcClient jdbc;

	JdbcSettings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int value(Rule rule) {
		return this.jdbc.sql("SELECT value FROM settings.rule_value WHERE rule_key = :key")
			.param("key", rule.name())
			.query(Integer.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("No value for rule " + rule + "; the settings migration seeds it"));
	}

	@Override
	public boolean allowsSchoolEmail(String address) {
		String domain = domainOf(address);
		if (domain == null) {
			return false;
		}
		return this.jdbc.sql("""
				SELECT count(*) FROM settings.school_domain
				WHERE :domain = domain OR right(:domain, char_length(domain) + 1) = '.' || domain
				""").param("domain", domain).query(Long.class).single() > 0;
	}

	@Override
	public List<String> schoolDomains() {
		return this.jdbc.sql("SELECT domain FROM settings.school_domain ORDER BY domain").query(String.class).list();
	}

	/** The lower-case domain of an address with exactly one {@code @} and text on both sides. */
	static @Nullable String domainOf(String address) {
		String trimmed = address.strip();
		int at = trimmed.indexOf('@');
		if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.length() - 1) {
			return null;
		}
		return trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
	}

}
