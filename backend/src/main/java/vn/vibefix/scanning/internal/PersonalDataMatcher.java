package vn.vibefix.scanning.internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;

import vn.vibefix.scanning.internal.ScanRules.PersonalData;
import vn.vibefix.scanning.internal.ScanRules.PersonalKind;

/**
 * The Personal-Data Check on one file's text (FR-65, addendum §B6). It counts distinct Vietnamese
 * phone numbers and email addresses, citizen IDs in a data context, and, in a data file
 * ({@code .sql}, {@code .csv}, {@code .tsv}, {@code .json}), columns named for personal data. A file
 * is a hit past the thresholds of {@link PersonalData}; the hit carries its kinds, the lines holding
 * a personal value, and two masked samples. Raw values stay inside the scan.
 */
final class PersonalDataMatcher {

	/** A file holding personal data. */
	record Hit(Set<PersonalKind> kinds, int lines, List<String> samples, String hash) {
	}

	/** The rule ID every personal-data Finding carries; its fingerprint is per file path. */
	static final String RULE_ID = "personal-data";

	static final int SAMPLES = 2;

	private static final Pattern CSV_SPLIT = Pattern.compile("[,;\\t]");

	private static final Pattern SQL_COLUMNS = Pattern
		.compile("(?i)(?:insert\\s+into|copy)\\s+[^\\s(]+\\s*\\(([^)]{1,1000})\\)");

	private static final Pattern JSON_KEY = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_ -]{0,40})\"\\s*:");

	private static final Pattern SQL_DATA = Pattern.compile("(?i)\\binsert\\s+into\\b|\\bcopy\\s+[^\\s(]+.*\\bfrom\\s+stdin\\b|\\bvalues\\s*\\(");

	private final PersonalData rules;

	PersonalDataMatcher(PersonalData rules) {
		this.rules = rules;
	}

	Optional<Hit> find(String path, String text) {
		if (this.rules.exemptFiles().matcher(path).find()) {
			return Optional.empty();
		}
		boolean dataFile = this.rules.dataFiles().matcher(path).find();
		Columns columns = dataFile ? columns(path, text) : Columns.NONE;
		Set<String> phones = new LinkedHashSet<>();
		Set<String> emails = new LinkedHashSet<>();
		Set<String> ids = new LinkedHashSet<>();
		List<String> samples = new ArrayList<>();
		int lines = 0;
		for (String line : text.split("\n", -1)) {
			boolean personal = false;
			// Emails before phones, as the CUS-5 frame shows a customer's line.
			Matcher email = this.rules.email().matcher(line);
			while (email.find()) {
				String address = email.group().toLowerCase(Locale.ROOT);
				if (this.rules.ignoredEmails().matcher(address).find()) {
					continue;
				}
				if (emails.add(address)) {
					sample(samples, maskEmail(email.group(1), email.group(2)));
				}
				personal = true;
			}
			Matcher phone = this.rules.phone().matcher(line);
			while (phone.find()) {
				String digits = normalize(phone.group());
				if (phones.add(digits)) {
					sample(samples, maskPhone(digits));
				}
				personal = true;
			}
			Matcher id = (columns.citizenId() ? this.rules.citizenId() : this.rules.citizenIdInContext()).matcher(line);
			while (id.find()) {
				if (ids.add(id.group(1))) {
					sample(samples, maskId(id.group(1)));
				}
				personal = true;
			}
			if (personal) {
				lines++;
			}
		}
		Set<PersonalKind> kinds = EnumSet.noneOf(PersonalKind.class);
		boolean dumped = dataFile && columns.personal() && columns.rows()
				&& phones.size() + emails.size() + ids.size() >= this.rules.dataFileValues();
		if (!ids.isEmpty()) {
			kinds.add(PersonalKind.CITIZEN_ID);
		}
		if (emails.size() >= this.rules.emails() || dumped && !emails.isEmpty()) {
			kinds.add(PersonalKind.EMAIL);
		}
		if (phones.size() >= this.rules.phones() || dumped && !phones.isEmpty()) {
			kinds.add(PersonalKind.PHONE);
		}
		if (dumped) {
			kinds.add(PersonalKind.DATA_COLUMNS);
		}
		if (kinds.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Hit(kinds, Math.max(lines, 1), List.copyOf(samples), SecretMatcher.hash(RULE_ID, path)));
	}

	/** Whether the data file names a personal-data column, a citizen-ID one, and holds rows. */
	private record Columns(boolean personal, boolean citizenId, boolean rows) {

		static final Columns NONE = new Columns(false, false, false);

	}

	private Columns columns(String path, String text) {
		String lower = path.toLowerCase(Locale.ROOT);
		List<String> names = new ArrayList<>();
		boolean rows;
		if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
			String[] lines = text.strip().split("\n");
			for (String name : CSV_SPLIT.split(lines[0])) {
				names.add(name);
			}
			rows = lines.length > 1;
		}
		else if (lower.endsWith(".sql")) {
			Matcher insert = SQL_COLUMNS.matcher(text);
			while (insert.find()) {
				for (String name : insert.group(1).split(",")) {
					names.add(name);
				}
			}
			rows = SQL_DATA.matcher(text).find();
		}
		else {
			Matcher key = JSON_KEY.matcher(text);
			while (key.find()) {
				names.add(key.group(1));
			}
			rows = true;
		}
		boolean personal = false;
		boolean citizenId = false;
		for (String raw : names) {
			String name = raw.strip().replaceAll("^[\"'`\\[]+|[\"'`\\]]+$", "");
			personal |= this.rules.columns().matcher(name).matches();
			citizenId |= this.rules.citizenIdColumns().matcher(name).matches();
		}
		return new Columns(personal, citizenId, rows);
	}

	private static void sample(List<String> samples, String masked) {
		if (samples.size() < SAMPLES) {
			samples.add(masked);
		}
	}

	/** The digits of a Vietnamese number, {@code +84} written as {@code 0}. */
	static String normalize(String phone) {
		String digits = phone.replaceAll("[^0-9]", "");
		return phone.strip().startsWith("+84") ? "0" + digits.substring(2) : digits;
	}

	/** {@code 09•• ••• •12}, as on the CUS-5 frame: the first and last 2 digits. */
	static String maskPhone(String digits) {
		return digits.substring(0, 2) + "•• ••• •" + digits.substring(digits.length() - 2);
	}

	/** {@code ng••••@gmail.com}: at most the first 2 characters of the local part, then the domain. */
	static String maskEmail(String local, String domain) {
		return local.substring(0, Math.min(local.length() > 2 ? 2 : 1, local.length())) + "••••@" + domain;
	}

	/** The first digit and the last 2 of a citizen ID. */
	static String maskId(String id) {
		return id.charAt(0) + "•".repeat(id.length() - 3) + id.substring(id.length() - 2);
	}

}
