package vn.vibefix.authz.fixtures;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/**
 * The anonymous caller and one unrelated account per role, each a real account signed in through
 * the session, so the request passes the same per-request account check as a real one (AD-19).
 * Subject state:
 * <ul>
 * <li>{@code locked=true}: the account is locked, so the request arrives anonymous;</li>
 * <li>{@code mentor=PENDING|APPROVED|REJECTED|REVOKED}: a Mentor's approval status (default
 * {@code APPROVED}).</li>
 * </ul>
 */
public class RoleCallers implements MatrixFixture {

	private final JdbcClient jdbc;

	public RoleCallers(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<String> stateKeys() {
		return Set.of("locked", "mentor");
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
		if (role == null) {
			return Optional.empty();
		}
		MentorApprovalStatus mentor = state.containsKey("mentor") ? MentorApprovalStatus.valueOf(state.get("mentor"))
				: null;
		if (mentor != null && role != Role.MENTOR) {
			throw new AssertionError("Only a Mentor has an approval status");
		}
		String lockReason = "true".equals(state.get("locked")) ? "matrix: locked" : null;
		return Optional.of(signedIn(this.jdbc, role, mentor, lockReason));
	}

	/** A request from a new account with {@code role}, signed in through the session. */
	public static RequestPostProcessor signedIn(JdbcClient jdbc, Role role, @Nullable MentorApprovalStatus mentor,
			@Nullable String lockReason) {
		UUID id = IdentityTestSupport.insertAccount(jdbc, role, mentor, lockReason);
		return authentication(IdentityTestSupport.authentication(id, role));
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

	/** A request from a new account with {@code role}, set directly; see {@link #as(Caller)}. */
	public static RequestPostProcessor as(Role role) {
		return as(Caller.account(UUID.randomUUID(), role));
	}

}
