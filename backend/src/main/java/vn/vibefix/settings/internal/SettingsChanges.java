package vn.vibefix.settings.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.settings.Rule;
import vn.vibefix.settings.Settings;
import vn.vibefix.settings.SettingsAdministration;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/** {@link SettingsAdministration} over the {@code settings} schema; only real changes are recorded. */
@Component
class SettingsChanges implements SettingsAdministration {

	/** The domain shape {@code settings.school_domain} checks too. */
	private static final Pattern DOMAIN = Pattern
		.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$");

	private final JdbcClient jdbc;

	private final Settings settings;

	private final IdGenerator ids;

	private final Clock clock;

	SettingsChanges(JdbcClient jdbc, Settings settings, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	public void validate(Map<Rule, Integer> values, List<String> domains) {
		values.forEach((rule, value) -> {
			if (value == null || value < rule.min() || value > rule.max()) {
				throw new ApiException(ErrorCode.VALIDATION, SettingsReason.RULE_OUT_OF_BOUNDS,
						Map.of("rule", rule.name(), "min", rule.min(), "max", rule.max()));
			}
		});
		normalized(domains);
	}

	@Override
	@Transactional
	public List<Change> save(UUID administrator, Map<Rule, Integer> values, List<String> domains) {
		validate(values, domains);
		Set<String> wanted = normalized(domains);
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		List<Change> changes = new ArrayList<>();
		for (Rule rule : Rule.values()) {
			Integer value = values.get(rule);
			if (value == null) {
				continue;
			}
			int old = this.settings.value(rule);
			if (old != value) {
				this.jdbc.sql("UPDATE settings.rule_value SET value = :value, updated_at = :now WHERE rule_key = :key")
					.param("key", rule.name())
					.param("value", value)
					.param("now", Timestamp.from(now))
					.update();
				changes.add(record(administrator, "RULE", rule, old, value, null, now));
			}
		}
		Set<String> current = new TreeSet<>(this.settings.schoolDomains());
		for (String domain : current) {
			if (!wanted.contains(domain)) {
				this.jdbc.sql("DELETE FROM settings.school_domain WHERE domain = :domain").param("domain", domain).update();
				changes.add(record(administrator, "DOMAIN_REMOVED", null, null, null, domain, now));
			}
		}
		for (String domain : wanted) {
			if (!current.contains(domain)) {
				this.jdbc.sql("INSERT INTO settings.school_domain (domain, added_at) VALUES (:domain, :now)")
					.param("domain", domain)
					.param("now", Timestamp.from(now))
					.update();
				changes.add(record(administrator, "DOMAIN_ADDED", null, null, null, domain, now));
			}
		}
		return changes;
	}

	@Override
	public List<Change> history(int limit) {
		return this.jdbc.sql("""
				SELECT id, kind, rule_key, old_value, new_value, domain, changed_at FROM settings.change
				ORDER BY changed_at DESC, id DESC LIMIT :limit
				""").param("limit", limit).query(SettingsChanges::change).list();
	}

	private Change record(UUID administrator, String kind, @Nullable Rule rule, @Nullable Integer old,
			@Nullable Integer value, @Nullable String domain, Instant now) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO settings.change (id, kind, rule_key, old_value, new_value, domain, changed_by, changed_at)
				VALUES (:id, :kind, :rule, :old, :value, :domain, :by, :now)
				""")
			.param("id", id)
			.param("kind", kind)
			.param("rule", rule == null ? null : rule.name())
			.param("old", old)
			.param("value", value)
			.param("domain", domain)
			.param("by", administrator)
			.param("now", Timestamp.from(now))
			.update();
		return new Change(id, kind, rule, old, value, domain, now);
	}

	/** The domains in lower case without spaces, each a domain name. */
	private static Set<String> normalized(List<String> domains) {
		Set<String> normalized = new TreeSet<>();
		for (String domain : domains) {
			String clean = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
			if (!DOMAIN.matcher(clean).matches() || clean.length() > 253) {
				throw new ApiException(ErrorCode.VALIDATION, SettingsReason.DOMAIN_INVALID, Map.of("domain", String.valueOf(domain)));
			}
			normalized.add(clean);
		}
		return normalized;
	}

	private static Change change(ResultSet rs, int n) throws SQLException {
		String rule = rs.getString("rule_key");
		return new Change(rs.getObject("id", UUID.class), rs.getString("kind"), rule == null ? null : Rule.valueOf(rule),
				(Integer) rs.getObject("old_value"), (Integer) rs.getObject("new_value"), rs.getString("domain"),
				rs.getTimestamp("changed_at").toInstant());
	}

}
