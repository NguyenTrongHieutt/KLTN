package vn.vibefix.scanning.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import com.google.re2j.Matcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.scanning.internal.ScanRules.Rule;

/**
 * Applies {@link ScanRules} to one file's text (AD-18). A value is reported once per file: a later
 * rule never reports a span an earlier rule claimed, and placeholders, excluded matches, and values
 * failing their rule's check are dropped. The raw value stays inside the scan: callers receive only
 * {@link Match#masked()} and {@link Match#hash()}.
 */
final class SecretMatcher {

	/** One credential found in a file. */
	record Match(String ruleId, String type, String masked, String hash) {
	}

	/** The fixed run of bullets between the shown ends of a masked value (UX-DR31). */
	static final String BULLETS = "••••••••";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ScanRules rules;

	SecretMatcher(ScanRules rules) {
		this.rules = rules;
	}

	List<Match> find(String path, String text) {
		String lowerCase = text.toLowerCase(Locale.ROOT);
		List<int[]> claimed = new ArrayList<>();
		List<Match> matches = new ArrayList<>();
		for (Rule rule : this.rules.rules()) {
			if (!rule.appliesTo(path) || !rule.mayMatch(lowerCase)) {
				continue;
			}
			Matcher matcher = rule.pattern().matcher(text);
			while (matcher.find()) {
				int start = matcher.start(1);
				int end = matcher.end(1);
				if (start < 0 || overlaps(claimed, start, end)) {
					continue;
				}
				String value = matcher.group(1);
				if (!isCredential(rule, matcher.group(), value)) {
					continue;
				}
				claimed.add(new int[] { start, end });
				matches.add(new Match(rule.id(), rule.type(), mask(value), hash(rule.id(), value)));
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

	/**
	 * At most a quarter of the value and never more than its first and last 4 characters (FR-8,
	 * AD-18): {@code k = min(4, length / 8)} characters at each end around {@link #BULLETS}.
	 */
	static String mask(String value) {
		int shown = Math.min(4, value.length() / 8);
		return value.substring(0, shown) + BULLETS + value.substring(value.length() - shown);
	}

	/** {@code sha256(ruleId:value)} in hexadecimal; the platform keys it into the fingerprint. */
	static String hash(String ruleId, String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest((ruleId + ":" + value).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every Java platform has SHA-256", ex);
		}
	}

}
