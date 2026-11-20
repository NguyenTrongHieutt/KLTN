package vn.vibefix.scanning.internal;

import org.springframework.stereotype.Component;

import vn.vibefix.scanning.CredentialCheck;

/**
 * {@link CredentialCheck} on the classpath rules (FR-66, AD-18): the credential rules as for a plain text file, which
 * no {@code paths} pattern of {@code rules.yaml} names, so a rule scoped to {@code .env} files never applies.
 */
@Component
class RulesCredentialCheck implements CredentialCheck {

	/** The path a value is checked as: plain text, outside every pattern a rule's {@code paths} holds. */
	static final String PATH = "value.txt";

	private final SecretMatcher secrets;

	RulesCredentialCheck() {
		this.secrets = new SecretMatcher(ScanRules.load());
	}

	@Override
	public boolean looksLikeCredential(String value) {
		return !this.secrets.locate(PATH, value).isEmpty();
	}

}
