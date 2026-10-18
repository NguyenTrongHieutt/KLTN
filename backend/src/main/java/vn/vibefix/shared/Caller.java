package vn.vibefix.shared;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who sends the current request (AD-20): anonymous, or an account with its permanent role. It
 * carries nothing that can change, so policies read approval, verification, and lock status from
 * the database on every request, never from here (AD-19).
 * @param accountId the account; {@code null} exactly when the caller is anonymous
 * @param role the account's role; {@code null} exactly when the caller is anonymous
 */
public record Caller(@Nullable UUID accountId, @Nullable Role role) {

	/**
	 * The request attribute holding the signed-in caller. Only server code sets it, after the sign-in
	 * filter has resolved the session; it is never read from anything the client sends.
	 */
	public static final String REQUEST_ATTRIBUTE = Caller.class.getName();

	private static final Caller ANONYMOUS = new Caller(null, null);

	public Caller {
		if ((accountId == null) != (role == null)) {
			throw new IllegalArgumentException("A caller has both an account and a role, or neither");
		}
	}

	public static Caller anonymous() {
		return ANONYMOUS;
	}

	public static Caller account(UUID accountId, Role role) {
		return new Caller(Objects.requireNonNull(accountId, "accountId"), Objects.requireNonNull(role, "role"));
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
