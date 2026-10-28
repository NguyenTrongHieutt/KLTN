package vn.vibefix.authz.fixtures;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.Caller;
import vn.vibefix.support.MutableClock;

/**
 * The global callers (AD-20, Story 20.5): {@link MatrixCaller#ANONYMOUS}, the unrelated Google accounts
 * {@link MatrixCaller#USER} (GitHub linked) and {@link MatrixCaller#USER_UNVERIFIED} (not linked), and an
 * {@link MatrixCaller#ADMINISTRATOR} without a grant, each a new account, so the request passes the same
 * per-request account check as a real one (AD-19): the Administrator signs in through a stored session, and a
 * user's request carries its authentication itself ({@code IdentityTestSupport.signedIn}). No caller takes a subject state: a
 * locked account is {@link MatrixCaller#LOCKED}, and the GitHub link is what tells the two users apart.
 */
public class RoleCallers implements MatrixFixture {

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	public RoleCallers(JdbcClient jdbc, SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.sessions = sessions;
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		return switch (caller) {
			case ANONYMOUS -> Optional.of(request -> request);
			case USER -> Optional.of(googleUser(this.jdbc, this.sessions, true));
			case USER_UNVERIFIED -> Optional.of(googleUser(this.jdbc, this.sessions, false));
			case ADMINISTRATOR -> Optional.of(IdentityTestSupport.signedIn(this.sessions,
					IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null), Role.ADMINISTRATOR,
					MutableClock.DEFAULT_INSTANT));
			default -> Optional.empty();
		};
	}

	/**
	 * A request from a new Google account, as a first Google sign-in creates it (no role, N-10 accepted), with
	 * GitHub linked when {@code linked}, signed in through the session.
	 */
	public static RequestPostProcessor googleUser(JdbcClient jdbc, SessionRepository<? extends Session> sessions,
			boolean linked) {
		UUID id = IdentityTestSupport.insertGoogleAccount(jdbc, "google-matrix-" + UUID.randomUUID(), "3.1");
		if (linked) {
			IdentityTestSupport.linkGitHub(jdbc, id);
		}
		return IdentityTestSupport.signedIn(sessions, id, null, MutableClock.DEFAULT_INSTANT);
	}

	/**
	 * A request whose caller is set directly, as the sign-in filter sets it, for harness fixtures that
	 * stand in for relationships no module models.
	 */
	public static RequestPostProcessor as(Caller caller) {
		return request -> {
			request.setAttribute(Caller.REQUEST_ATTRIBUTE, caller);
			return request;
		};
	}

}
