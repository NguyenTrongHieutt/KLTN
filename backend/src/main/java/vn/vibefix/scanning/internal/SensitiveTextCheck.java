package vn.vibefix.scanning.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import vn.vibefix.scanning.SensitiveText;
import vn.vibefix.scanning.internal.PersonalDataMatcher.Span;
import vn.vibefix.scanning.internal.SecretMatcher.Located;

/**
 * {@link SensitiveText} on the classpath rules (FR-47, AD-18): the credential rules as for a file,
 * then the Personal-Data Check with its bulk thresholds, which a few addresses in a message do not
 * meet. Overlapping matches keep the credential.
 */
@Component
class SensitiveTextCheck implements SensitiveText {

	/** The path a message is checked as: plain text, outside every data-file and exempt pattern. */
	static final String PATH = "chat/message.txt";

	private final SecretMatcher secrets;

	private final PersonalDataMatcher personalData;

	SensitiveTextCheck() {
		ScanRules rules = ScanRules.load();
		this.secrets = new SecretMatcher(rules);
		this.personalData = new PersonalDataMatcher(rules.personalData());
	}

	@Override
	public Result check(String text) {
		String checked = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
		record Replacement(int start, int end, Match match) {
		}
		List<Replacement> replacements = new ArrayList<>();
		for (Located located : this.secrets.locate(PATH, checked)) {
			replacements.add(new Replacement(located.start(), located.end(),
					new Match(Kind.CREDENTIAL, located.match().type(), located.match().masked())));
		}
		if (this.personalData.find(PATH, checked).isPresent()) {
			for (Span span : this.personalData.spans(checked)) {
				boolean overlaps = replacements.stream()
					.anyMatch(other -> span.start() < other.end() && other.start() < span.end());
				if (!overlaps) {
					replacements.add(new Replacement(span.start(), span.end(),
							new Match(Kind.PERSONAL_DATA, "PERSONAL_DATA", span.masked())));
				}
			}
		}
		replacements.sort(Comparator.comparingInt(Replacement::start));
		StringBuilder masked = new StringBuilder();
		int at = 0;
		for (Replacement replacement : replacements) {
			masked.append(checked, at, replacement.start()).append(replacement.match().masked());
			at = replacement.end();
		}
		masked.append(checked.substring(at));
		return new Result(replacements.stream().map(Replacement::match).toList(), masked.toString());
	}

}
