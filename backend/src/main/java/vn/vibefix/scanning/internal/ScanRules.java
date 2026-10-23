package vn.vibefix.scanning.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code scanning/rules.yaml} compiled with RE2/J (AD-18, SG-13): its version, the placeholder values
 * that are never credentials, the credential rules in their order, and the Personal-Data Check
 * (FR-65). Loading fails on any pattern that does not compile, a rule that lacks its capturing group
 * or repeats an ID, or a missing threshold, so a broken rule set never scans.
 */
record ScanRules(int version, Pattern placeholders, List<Rule> rules, PersonalData personalData) {

	static final String RESOURCE = "scanning/rules.yaml";

	/** The {@code check} that keeps only a JWT whose {@code role} is {@code service_role}. */
	static final String JWT_SERVICE_ROLE = "jwt-service-role";

	/**
	 * One rule.
	 * @param keywords lower-case; the rule runs only on text containing one, or always when empty
	 * @param paths only files whose path matches, or every file
	 * @param exclude a match containing this is not a credential
	 * @param check a test of the value, {@value ScanRules#JWT_SERVICE_ROLE} or none
	 */
	record Rule(String id, String type, Pattern pattern, Set<String> keywords, @Nullable Pattern paths,
			@Nullable Pattern exclude, @Nullable String check) {

		boolean appliesTo(String path) {
			return this.paths == null || this.paths.matcher(path).find();
		}

		boolean mayMatch(String lowerCaseText) {
			return this.keywords.isEmpty() || this.keywords.stream().anyMatch(lowerCaseText::contains);
		}

	}

	/** The kinds of personal data a Finding names, in the order its type lists them. */
	enum PersonalKind {
		CITIZEN_ID, EMAIL, PHONE, DATA_COLUMNS
	}

	/**
	 * The Personal-Data Check (FR-65, addendum §B6); see {@code rules.yaml} for each field.
	 * @param labels the Customer's word for each kind but {@code DATA_COLUMNS}, then {@code suffix} and
	 * {@code dataFile}
	 */
	record PersonalData(int phones, int emails, int dataFileValues, Pattern phone, Pattern email,
			Pattern ignoredEmails, Pattern citizenIdInContext, Pattern citizenId, Pattern dataFiles, Pattern columns,
			Pattern citizenIdColumns, Pattern exemptFiles, Pattern extracted, Pattern notExtracted,
			Map<String, String> labels) {

		/** The type a personal-data Finding shows, for example "Email và số điện thoại khách hàng". */
		String label(Set<PersonalKind> kinds) {
			List<String> words = java.util.stream.Stream.of(PersonalKind.CITIZEN_ID, PersonalKind.EMAIL, PersonalKind.PHONE)
				.filter(kinds::contains)
				.map(kind -> this.labels.get(kind.name()))
				.toList();
			if (words.isEmpty()) {
				return this.labels.get("dataFile");
			}
			String joined = words.size() == 1 ? words.getFirst()
					: String.join(", ", words.subList(0, words.size() - 1)) + " và " + words.getLast();
			return Character.toUpperCase(joined.charAt(0)) + joined.substring(1) + " " + this.labels.get("suffix");
		}

	}

	ScanRules {
		rules = List.copyOf(rules);
	}

	/** The rules on the classpath. */
	static ScanRules load() {
		try (InputStream in = ScanRules.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the classpath");
			}
			return parse(new Yaml(new SafeConstructor(new LoaderOptions())).load(in));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	static ScanRules parse(Object document) {
		if (!(document instanceof Map<?, ?> root) || !(root.get("version") instanceof Integer version)
				|| !(root.get("rules") instanceof List<?> entries) || entries.isEmpty()) {
			throw new IllegalStateException(RESOURCE + " needs a version and at least one rule");
		}
		Pattern placeholders = compile("placeholders", text(root, "placeholders", true));
		Set<String> ids = new HashSet<>();
		List<Rule> rules = entries.stream().map(entry -> {
			if (!(entry instanceof Map<?, ?> rule)) {
				throw new IllegalStateException(RESOURCE + " holds a rule that is not a map");
			}
			String id = text(rule, "id", true);
			if (!id.matches("[a-z0-9-]{1,60}") || !ids.add(id)) {
				throw new IllegalStateException("Rule ID " + id + " is not lower-case kebab or is repeated");
			}
			Pattern pattern = compile(id, text(rule, "pattern", true));
			if (pattern.groupCount() < 1) {
				throw new IllegalStateException("Rule " + id + " has no capturing group for the credential");
			}
			String check = text(rule, "check", false);
			if (check != null && !JWT_SERVICE_ROLE.equals(check)) {
				throw new IllegalStateException("Rule " + id + " names an unknown check " + check);
			}
			Object keywords = rule.get("keywords");
			Set<String> words = keywords == null ? Set.of()
					: Set.copyOf(((List<Object>) keywords).stream()
						.map(word -> String.valueOf(word).toLowerCase(Locale.ROOT))
						.toList());
			String paths = text(rule, "paths", false);
			String exclude = text(rule, "exclude", false);
			return new Rule(id, text(rule, "type", true), pattern, words, paths == null ? null : compile(id, paths),
					exclude == null ? null : compile(id, exclude), check);
		}).toList();
		return new ScanRules(version, placeholders, rules, personalData(root.get("personalData")));
	}

	private static PersonalData personalData(Object section) {
		if (!(section instanceof Map<?, ?> data) || !(data.get("thresholds") instanceof Map<?, ?> thresholds)
				|| !(data.get("labels") instanceof Map<?, ?> labels)) {
			throw new IllegalStateException(RESOURCE + " needs personalData with thresholds and labels");
		}
		Map<String, String> words = new java.util.HashMap<>();
		for (String key : List.of("CITIZEN_ID", "EMAIL", "PHONE", "suffix", "dataFile")) {
			words.put(key, text(labels, key, true));
		}
		return new PersonalData(threshold(thresholds, "phones"), threshold(thresholds, "emails"),
				threshold(thresholds, "dataFileValues"), pattern(data, "phone"), pattern(data, "email"),
				pattern(data, "ignoredEmails"), pattern(data, "citizenIdInContext"), pattern(data, "citizenId"),
				pattern(data, "dataFiles"), pattern(data, "columns"), pattern(data, "citizenIdColumns"),
				pattern(data, "exemptFiles"), pattern(data, "extracted"), pattern(data, "notExtracted"), Map.copyOf(words));
	}

	private static int threshold(Map<?, ?> thresholds, String key) {
		if (!(thresholds.get(key) instanceof Integer value) || value < 1) {
			throw new IllegalStateException(RESOURCE + " needs a positive personalData threshold " + key);
		}
		return value;
	}

	private static Pattern pattern(Map<?, ?> data, String key) {
		return compile("personalData." + key, text(data, key, true));
	}

	private static @Nullable String text(Map<?, ?> map, String key, boolean required) {
		Object value = map.get(key);
		if (value == null || String.valueOf(value).isBlank()) {
			if (required) {
				throw new IllegalStateException(RESOURCE + " lacks " + key + " in " + map.get("id"));
			}
			return null;
		}
		return String.valueOf(value);
	}

	private static Pattern compile(String owner, String regex) {
		try {
			return Pattern.compile(regex);
		}
		catch (PatternSyntaxException ex) {
			throw new IllegalStateException("Rule " + owner + " does not compile with RE2/J: " + ex.getDescription(), ex);
		}
	}

}
