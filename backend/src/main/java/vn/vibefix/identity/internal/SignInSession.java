package vn.vibefix.identity.internal;

import java.io.Serializable;
import java.time.Instant;
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
 * What the session holds between the Google callback and the account (AD-19). Every value is
 * serializable, because sessions live in PostgreSQL, and none is a token.
 */
final class SignInSession {

	/**
	 * A Google sign-in waiting on PUB-3 for the personal-data notice N-10 (NFR-9). The account exists
	 * already, but the session holds no account until the notice is accepted.
	 */
	static final String PENDING_WELCOME = SignInSession.class.getName() + ".pendingWelcome";

	/** A sign-in refused because the account is locked, with the reason to show. */
	static final String LOCKED = SignInSession.class.getName() + ".locked";

	/** Where to go after a sign-in, a relative path the SPA asked for with {@code quay-lai}. */
	static final String RETURN_TO = SignInSession.class.getName() + ".returnTo";

	/** A GitHub link started from this session and not returned yet (Story 19.2); used once. */
	static final String PENDING_LINK = SignInSession.class.getName() + ".pendingLink";

	private SignInSession() {
	}

	/**
	 * A signed-in Google account that has not accepted the current N-10 yet (PUB-3 "Chào mừng").
	 * @param displayName the name PUB-3 greets it by
	 */
	record PendingWelcome(UUID accountId, String displayName) implements Serializable {
	}

	record Locked(String reason) implements Serializable {
	}

	/**
	 * A GitHub link on its way to GitHub (AD-10, RFC 7636): the {@code state} it must come back with, the
	 * PKCE verifier its code is exchanged with, what it is for, where to return, and who started it when.
	 * The verifier is a secret of the session, never logged or returned, and gone once the link returns.
	 * @param redirectUri the callback the authorization request named, which the exchange names again
	 * @param returnTo a relative path inside the SPA, or {@code null} for ACC-2
	 */
	record PendingLink(UUID accountId, String state, String codeVerifier, GitHubLinksImpl.Purpose purpose,
			String redirectUri, @Nullable String returnTo, Instant startedAt) implements Serializable {

		@Override
		public String toString() {
			return "PendingLink[accountId=" + this.accountId + ", purpose=" + this.purpose + "]";
		}

	}

	/** Forgets every sign-in state, before a new one is set. */
	static void clear(HttpSession session) {
		session.removeAttribute(PENDING_WELCOME);
		session.removeAttribute(LOCKED);
	}

	/**
	 * The authenticated account in the security context: its ID and, for an account from before Google
	 * sign-in or an Administrator, its permanent role; a Google account has none (Story 20.1 brings the
	 * kind). Approval and lock status are read from the database on every request, never from here
	 * (AD-19).
	 */
	static final class AccountAuthentication extends AbstractAuthenticationToken {

		private final UUID accountId;

		private final @Nullable Role role;

		AccountAuthentication(UUID accountId, @Nullable Role role) {
			super(authorities(role));
			this.accountId = accountId;
			this.role = role;
			setAuthenticated(true);
		}

		UUID accountId() {
			return this.accountId;
		}

		@Nullable Role role() {
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

		private static Collection<? extends GrantedAuthority> authorities(@Nullable Role role) {
			return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
		}

	}

}
