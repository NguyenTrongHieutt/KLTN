package vn.vibefix.authz;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Builds part of the world for a matrix row (AD-20). Implementations in
 * {@code vn.vibefix.authz.fixtures} are found and created by the suite (with Spring autowiring), so
 * a story adds its callers, objects, and canaries by adding a fixture class there, never by
 * changing the suite.
 */
public interface MatrixFixture {

	/** Seeds this fixture's objects, path values, and canaries before the caller is realized. */
	default void seed(MatrixWorld world) {
	}

	/**
	 * Makes a request act as {@code caller} in {@code state}; empty when this fixture does not
	 * realize that caller. Exactly one fixture must realize each caller a row uses.
	 * @param state the row's {@code subject_state}, restricted to {@link #stateKeys()}
	 */
	default Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state,
			MatrixWorld world) {
		return Optional.empty();
	}

	/** The {@code subject_state} keys this fixture understands for the callers it realizes. */
	default Set<String> stateKeys() {
		return Set.of();
	}

}
