package vn.vibefix.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.ApiException;

/**
 * ADM-10 (FR-61, FR-63): saving the rule values and the school allowlist, each real change recorded with
 * its old and new value. Values are read live, so a new value applies to what is decided after it; a
 * Job keeps the values it copied when it was published.
 */
public interface SettingsAdministration {

	/** @param kind {@code RULE}, {@code DOMAIN_ADDED}, or {@code DOMAIN_REMOVED} */
	record Change(UUID id, String kind, @Nullable Rule rule, @Nullable Integer oldValue, @Nullable Integer newValue,
			@Nullable String domain, Instant changedAt) {
	}

	/** @throws ApiException {@code VALIDATION RULE_OUT_OF_BOUNDS} or {@code DOMAIN_INVALID} */
	void validate(Map<Rule, Integer> values, List<String> domains);

	/**
	 * Saves the values given and replaces the allowlist with {@code domains}.
	 * @return the changes, empty when nothing changed
	 * @throws ApiException as {@link #validate}
	 */
	List<Change> save(UUID administrator, Map<Rule, Integer> values, List<String> domains);

	/** The latest changes, newest first. */
	List<Change> history(int limit);

}
