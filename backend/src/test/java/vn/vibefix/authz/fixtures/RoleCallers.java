package vn.vibefix.authz.fixtures;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
import vn.vibefix.shared.Kind;
import vn.vibefix.support.MutableClock;

/**
 * The anonymous caller, the role-less users of Story 20.3 ({@link MatrixCaller#USER} with GitHub linked and
 * {@link MatrixCaller#USER_UNVERIFIED} without), and one unrelated shim account per old role, each a real account
 * signed in through the session, so the request passes the same per-request account check as a real one (AD-19).
 * Subject state:
 * <ul>
 * <li>{@code locked=true}: the account is locked, so the request arrives anonymous;</li>
 * <li>{@code github=unlinked}: a shim account with no GitHub account linked, which the commands that
 * create work and the reads of "Tìm việc" (X1) refuse ({@code GITHUB_NOT_LINKED}); linked by default. No
 * Mentor has an approval status since Story 19.7. {@code USER_UNVERIFIED} is unlinked already.</li>
 * </ul>
 */
public class RoleCallers implements MatrixFixture {

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	public RoleCallers(JdbcClient jdbc, SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.sessions = sessions;
	}

	@Override
	public Set<String> stateKeys() {
		return Set.of("locked", "github");
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		Role role = switch (caller) {
			case CUSTOMER -> Role.CUSTOMER;
			case MENTOR -> Role.MENTOR;
			case STUDENT -> Role.STUDENT;
			case ADMINISTRATOR -> Role.ADMINISTRATOR;
			default -> null;
		};
		if (caller == MatrixCaller.ANONYMOUS) {
			if (!state.isEmpty()) {
				throw new AssertionError("An anonymous caller has no subject state: " + state);
			}
			return Optional.of(request -> request);
		}
		if (caller == MatrixCaller.USER || caller == MatrixCaller.USER_UNVERIFIED) {
			if (state.containsKey("github")) {
				throw new AssertionError("USER is linked and USER_UNVERIFIED is not; neither takes github=");
			}
			String lockReason = "true".equals(state.get("locked")) ? "matrix: locked" : null;
			return Optional.of(googleUser(this.jdbc, this.sessions, caller == MatrixCaller.USER, lockReason));
		}
		if (role == null) {
			return Optional.empty();
		}
		String github = state.get("github");
		if (github != null && (!github.equals("unlinked") || role == Role.ADMINISTRATOR)) {
			throw new AssertionError("github=unlinked is the one GitHub state, and only a person's account has one");
		}
		String lockReason = "true".equals(state.get("locked")) ? "matrix: locked" : null;
		return Optional.of(signedIn(this.jdbc, this.sessions, role, lockReason, github != null));
	}

	/** A request from a new account with {@code role}, signed in through the session. */
	public static RequestPostProcessor signedIn(JdbcClient jdbc, SessionRepository<? extends Session> sessions, Role role,
			@Nullable String lockReason) {
		return signedIn(jdbc, sessions, role, lockReason, false);
	}

	private static RequestPostProcessor signedIn(JdbcClient jdbc, SessionRepository<? extends Session> sessions, Role role,
			@Nullable String lockReason, boolean unlinked) {
		UUID id = IdentityTestSupport.insertAccount(jdbc, role, lockReason);
		if (unlinked) {
			IdentityTestSupport.unlinkGitHub(jdbc, id);
		}
		return IdentityTestSupport.signedIn(sessions, id, role, MutableClock.DEFAULT_INSTANT);
	}

	/**
	 * A request from a new Google account, as a first Google sign-in creates it (no role, N-10 accepted), with
	 * GitHub linked when {@code linked}, signed in through the session.
	 */
	public static RequestPostProcessor googleUser(JdbcClient jdbc, SessionRepository<? extends Session> sessions,
			boolean linked, @Nullable String lockReason) {
		UUID id = IdentityTestSupport.insertGoogleAccount(jdbc, "google-matrix-" + UUID.randomUUID(), "3.1");
		if (linked) {
			IdentityTestSupport.linkGitHub(jdbc, id);
		}
		if (lockReason != null) {
			IdentityTestSupport.lock(jdbc, id, lockReason);
		}
		return IdentityTestSupport.signedIn(sessions, id, null, MutableClock.DEFAULT_INSTANT);
	}

	/**
	 * A request whose caller is set directly, as the sign-in filter sets it, for harness fixtures that
	 * stand in for relationships no module models yet.
	 */
	public static RequestPostProcessor as(Caller caller) {
		return request -> {
			request.setAttribute(Caller.REQUEST_ATTRIBUTE, caller);
			return request;
		};
	}

	/** A request from a new account of the kind {@code role} gives, set directly; see {@link #as(Caller)}. */
	public static RequestPostProcessor as(Role role) {
		return as(Caller.account(UUID.randomUUID(), role == Role.ADMINISTRATOR ? Kind.ADMINISTRATOR : Kind.USER));
	}

}
