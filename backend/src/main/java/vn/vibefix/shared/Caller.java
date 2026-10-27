package vn.vibefix.shared;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who sends the current request (AD-20): anonymous, or an account of a {@link Kind}. It carries nothing that
 * can change, so policies read the capabilities (the GitHub link, the verifications, and the lock) from the
 * database on every request through {@code identity.Capabilities}, never from here (AD-19). It carries no role:
 * a person's rights in a Job come from their role in that Job ({@code job.roleOf}, AD-43), global screens check
 * the capabilities (Story 20.3), and an Administrator holds no role in any Job (FR-2).
 * @param accountId the account; {@code null} exactly when the caller is anonymous
 * @param kind whose account it is; set exactly when {@code accountId} is
 */
public record Caller(@Nullable UUID accountId, @Nullable Kind kind) {

	/**
	 * The request attribute holding the signed-in caller. Only server code sets it, after the sign-in
	 * filter has resolved the session; it is never read from anything the client sends.
	 */
	public static final String REQUEST_ATTRIBUTE = Caller.class.getName();

	private static final Caller ANONYMOUS = new Caller(null, null);

	public Caller {
		if ((accountId == null) != (kind == null)) {
			throw new IllegalArgumentException("A kind goes with an account, and an account has one");
		}
	}

	public static Caller anonymous() {
		return ANONYMOUS;
	}

	/** A signed-in account. */
	public static Caller account(UUID accountId, Kind kind) {
		return new Caller(Objects.requireNonNull(accountId, "accountId"), Objects.requireNonNull(kind, "kind"));
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

	/** The account of a signed-in caller. */
	public UUID requireAccountId() {
		if (this.accountId == null) {
			throw new IllegalStateException("The caller is anonymous");
		}
		return this.accountId;
	}

}
