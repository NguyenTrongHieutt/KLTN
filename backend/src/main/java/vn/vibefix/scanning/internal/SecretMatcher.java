package vn.vibefix.scanning.internal;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import com.google.re2j.Matcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.scanning.internal.ScanRules.Rule;

/**
 * Finds which credential rules of {@link ScanRules} match a text (AD-18). A value is reported once: a later rule
 * never reports a span an earlier rule claimed, and placeholders, excluded matches, and values failing their rule's
 * check are dropped. Only the ID of the rule that matched leaves the matcher, never the value.
 */
final class SecretMatcher {

	/** One credential found, named by the rule that found it. */
	record Match(String ruleId) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ScanRules rules;

	SecretMatcher(ScanRules rules) {
		this.rules = rules;
	}

	/** The credentials in {@code text} read as the file at {@code path}: a rule with {@code paths} needs a match. */
	List<Match> find(String path, String text) {
		return find(text, rule -> rule.appliesTo(path));
	}

	/** The credentials in {@code text} read as no file at all: a rule with {@code paths} never applies. */
	List<Match> find(String text) {
		return find(text, rule -> rule.paths() == null);
	}

	private List<Match> find(String text, Predicate<Rule> applies) {
		String lowerCase = text.toLowerCase(Locale.ROOT);
		List<int[]> claimed = new ArrayList<>();
		List<Match> matches = new ArrayList<>();
		for (Rule rule : this.rules.rules()) {
			if (!applies.test(rule) || !rule.mayMatch(lowerCase)) {
				continue;
			}
			Matcher matcher = rule.pattern().matcher(text);
			while (matcher.find()) {
				int start = matcher.start(1);
				int end = matcher.end(1);
				if (start < 0 || overlaps(claimed, start, end)) {
					continue;
				}
				if (!isCredential(rule, matcher.group(), matcher.group(1))) {
					continue;
				}
				claimed.add(new int[] { start, end });
				matches.add(new Match(rule.id()));
			}
		}
		return matches;
	}

	private boolean isCredential(Rule rule, String match, String value) {
		if (this.rules.placeholders().matcher(value).matches()) {
			return false;
		}
		if (rule.exclude() != null && rule.exclude().matcher(match).find()) {
			return false;
		}
		String role = jwtRole(value);
		if (ScanRules.JWT_SERVICE_ROLE.equals(rule.check())) {
			return "service_role".equals(role);
		}
		// Supabase's anon key is public by design (§B6): no rule reports it.
		return !"anon".equals(role);
	}

	private static boolean overlaps(List<int[]> claimed, int start, int end) {
		return claimed.stream().anyMatch(span -> start < span[1] && span[0] < end);
	}

	/** The {@code role} claim of a JWT, or {@code null} for anything that is not one. */
	static String jwtRole(String value) {
		String[] parts = value.split("\\.", -1);
		if (parts.length != 3 || !parts[0].startsWith("eyJ") || !parts[1].startsWith("eyJ")) {
			return null;
		}
		try {
			JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
			JsonNode role = payload.path("role");
			return role.isString() ? role.stringValue() : null;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

}
