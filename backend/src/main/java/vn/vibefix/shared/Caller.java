package vn.vibefix.shared;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who sends the current request (AD-20): anonymous, or an account of a {@link Kind}. It carries nothing that
 * can change, so policies read the capabilities (the GitHub link, the verifications, and the lock) from the
 * database on every request through {@code identity.Capabilities}, never from here (AD-19). An Administrator
 * holds no role in any Job (FR-2); a person's rights in a Job come from their role in that Job (Story 20.2).
 * @param accountId the account; {@code null} exactly when the caller is anonymous
 * @param kind whose account it is; set exactly when {@code accountId} is
 * @param role transitional: the account role of a row from before Google sign-in and of an Administrator,
 * read from the account row on every request and never from the session; {@code null} when the caller is
 * anonymous and for a Google account, which every role check refuses (deny by default, AD-20). Story 20.3
 * deletes it.
 */
public record Caller(@Nullable UUID accountId, @Nullable Kind kind, @Nullable Role role) {

	/**
	 * The request attribute holding the signed-in caller. Only server code sets it, after the sign-in
	 * filter has resolved the session; it is never read from anything the client sends.
	 */
	public static final String REQUEST_ATTRIBUTE = Caller.class.getName();

	private static final Caller ANONYMOUS = new Caller(null, null, null);

	public Caller {
		if ((accountId == null) != (kind == null)) {
			throw new IllegalArgumentException("A kind goes with an account, and an account has one");
		}
		if (accountId == null && role != null) {
			throw new IllegalArgumentException("A role needs an account");
		}
		if ((kind == Kind.ADMINISTRATOR) != (role == Role.ADMINISTRATOR)) {
			throw new IllegalArgumentException("An Administrator is exactly the ADMINISTRATOR kind");
		}
	}

	public static Caller anonymous() {
		return ANONYMOUS;
	}

	/**
	 * A signed-in account.
	 * @param role the transitional account role read from the account row, if it has one
	 */
	public static Caller account(UUID accountId, Kind kind, @Nullable Role role) {
		return new Caller(Objects.requireNonNull(accountId, "accountId"), Objects.requireNonNull(kind, "kind"), role);
	}

	public boolean isAnonymous() {
		return this.accountId == null;
	}

	/** A person's account, signed in. */
	public boolean isUser() {
		return this.kind == Kind.USER;
	}

	/** An Administrator, signed in. */
	public boolean isAdministrator() {
		return this.kind == Kind.ADMINISTRATOR;
	}

	/**
	 * Transitional: whether the account row carries {@code role}; a Google account carries none. Story 20.2
	 * replaces the per-Job checks with the role in the Job, and Story 20.3 deletes this with {@link #role}.
	 */
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
