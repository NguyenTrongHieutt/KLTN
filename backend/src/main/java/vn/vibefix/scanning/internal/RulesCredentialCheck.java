package vn.vibefix.scanning.internal;

import org.springframework.stereotype.Component;

import vn.vibefix.scanning.CredentialCheck;

/**
 * {@link CredentialCheck} on the classpath rules (FR-66, AD-18): every credential rule with no {@code paths}, since a
 * value is no file. A rule scoped to some files by its {@code paths}, such as {@code .env} files, never applies,
 * whatever pattern it names.
 */
@Component
class RulesCredentialCheck implements CredentialCheck {

	private final SecretMatcher secrets;

	RulesCredentialCheck() {
		this(ScanRules.load());
	}

	RulesCredentialCheck(ScanRules rules) {
		this.secrets = new SecretMatcher(rules);
	}

	@Override
	public boolean looksLikeCredential(String value) {
		return !this.secrets.find(value).isEmpty();
	}

}
