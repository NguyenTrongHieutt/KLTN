package vn.vibefix.authz.fixtures;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.shared.Caller;
import vn.vibefix.shared.Role;

/**
 * The anonymous caller and one unrelated account per role. There are no accounts in the database
 * yet, so the caller is set on the request directly, where the sign-in filter will set it; such a
 * caller has no subject state. When accounts exist, this fixture creates them and understands
 * their state ({@code locked}, {@code mentor}, {@code verified}).
 */
public class RoleCallers implements MatrixFixture {

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		return switch (caller) {
			case ANONYMOUS -> Optional.of(request -> request);
			case CUSTOMER -> Optional.of(as(Role.CUSTOMER));
			case MENTOR -> Optional.of(as(Role.MENTOR));
			case STUDENT -> Optional.of(as(Role.STUDENT));
			case ADMINISTRATOR -> Optional.of(as(Role.ADMINISTRATOR));
			default -> Optional.empty();
		};
	}

	/** A request from a new account with {@code role}. */
	public static RequestPostProcessor as(Role role) {
		return as(Caller.account(UUID.randomUUID(), role));
	}

	/** A request from {@code caller}. */
	public static RequestPostProcessor as(Caller caller) {
		return request -> {
			request.setAttribute(Caller.REQUEST_ATTRIBUTE, caller);
			return request;
		};
	}

}
