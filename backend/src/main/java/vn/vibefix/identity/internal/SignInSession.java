package vn.vibefix.identity.internal;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import vn.vibefix.shared.Role;

/**
 * What the session holds between the GitHub callback and the account (AD-19). Every value is
 * serializable, because sessions live in PostgreSQL, and none is a token in clear.
 */
final class SignInSession {

	/** A first sign-in whose role is not chosen yet; the token is sealed with the GitHub ID. */
	static final String PENDING_SIGN_UP = SignInSession.class.getName() + ".pendingSignUp";

	/** A sign-in refused because the account is locked, with the reason to show. */
	static final String LOCKED = SignInSession.class.getName() + ".locked";

	/** Where to go after a sign-in, a relative path the SPA asked for with {@code quay-lai}. */
	static final String RETURN_TO = SignInSession.class.getName() + ".returnTo";

	private SignInSession() {
	}

	/**
	 * A first sign-in waiting for its role (PUB-3).
	 * @param githubEmail the primary verified email GitHub gave, where notifications will go
	 * @param token the sign-in token, sealed to the GitHub account
	 */
	record PendingSignUp(long githubUserId, String githubLogin, @Nullable String githubAvatarUrl,
			@Nullable String githubEmail, TokenCipher.@Nullable Sealed token) implements Serializable {

		/** The associated data that binds the pending token to this GitHub account. */
		static String tokenAssociatedData(long githubUserId) {
			return "github:" + githubUserId;
		}

	}

	record Locked(String reason) implements Serializable {
	}

	/** Forgets every sign-in state, before a new one is set. */
	static void clear(HttpSession session) {
		session.removeAttribute(PENDING_SIGN_UP);
		session.removeAttribute(LOCKED);
	}

	/**
	 * The authenticated account in the security context: its ID and permanent role only. Approval and
	 * lock status are read from the database on every request, never from here (AD-19).
	 */
	static final class AccountAuthentication extends AbstractAuthenticationToken {

		private final UUID accountId;

		private final Role role;

		AccountAuthentication(UUID accountId, Role role) {
			super(authorities(role));
			this.accountId = accountId;
			this.role = role;
			setAuthenticated(true);
		}

		UUID accountId() {
			return this.accountId;
		}

		Role role() {
			return this.role;
		}

		@Override
		public Object getCredentials() {
			return "";
		}

		@Override
		public Object getPrincipal() {
			return this.accountId;
		}

		/** The principal name Spring Session indexes sessions by, so a lock can find them (SG-11). */
		@Override
		public String getName() {
			return this.accountId.toString();
		}

		private static Collection<? extends GrantedAuthority> authorities(Role role) {
			return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
		}

	}

}
