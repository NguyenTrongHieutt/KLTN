package vn.vibefix.scanning;

/**
 * Whether a short value looks like a credential (FR-66, AD-18): the credential rules of {@code scanning/rules.yaml},
 * with no AI, on the value as plain text. A rule scoped to some files by {@code paths} never applies, and nothing
 * looks for personal data. {@code job} refuses an External Access identifier this reads as a credential, since that
 * field holds accounts. No text people send is checked (D5).
 */
public interface CredentialCheck {

	/** Whether any credential rule matches {@code value}; the matched value never leaves the check. */
	boolean looksLikeCredential(String value);

}
