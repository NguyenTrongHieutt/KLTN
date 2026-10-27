package vn.vibefix.shared;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who sends the current request (AD-20): anonymous, or an account with its permanent role, if it has
 * one. It carries nothing that can change, so policies read the GitHub link, verification, and lock status
 * from the database on every request, never from here (AD-19).
 * @param accountId the account; {@code null} exactly when the caller is anonymous
 * @param role the role of an account from before Google sign-in, and an Administrator's; {@code null}
 * when the caller is anonymous, and for a Google account, which every role check refuses until
 * Epic 20 (deny by default, AD-20). Story 20.1 brings the account's kind instead.
 */
public record Caller(@Nullable UUID accountId, @Nullable Role role) {

	/**
	 * The request attribute holding the signed-in caller. Only server code sets it, after the sign-in
	 * filter has resolved the session; it is never read from anything the client sends.
	 */
	public static final String REQUEST_ATTRIBUTE = Caller.class.getName();

	private static final Caller ANONYMOUS = new Caller(null, null);

	public Caller {
		if (accountId == null && role != null) {
			throw new IllegalArgumentException("A role needs an account");
		}
	}

	public static Caller anonymous() {
		return ANONYMOUS;
	}

	public static Caller account(UUID accountId, @Nullable Role role) {
		return new Caller(Objects.requireNonNull(accountId, "accountId"), role);
	}

	public boolean isAnonymous() {
		return this.accountId == null;
	}

	public boolean hasRole(Role role) {
		return this.role == role;
	}

	/** The account of a signed-in caller. */
	public UUID requireAccountId() {
		if (this.accountId == null) {
			throw new IllegalStateException("The caller is anonymous");
		}
		return this.accountId;
	}

}
