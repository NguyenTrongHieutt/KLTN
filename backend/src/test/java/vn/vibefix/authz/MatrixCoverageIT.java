package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import vn.vibefix.shared.Access;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-20, SM-5: every vibefix handler lives under {@code /api} or {@code /webhooks}, where the gate
 * guards it; each declares its {@link Access} gate and an explicit HTTP method and has at least one
 * row in {@code authz/matrix.csv}; and every row names a handler that exists.
 */
class MatrixCoverageIT extends IntegrationTest {

	static final List<String> GATED_PREFIXES = List.of("/api/", "/webhooks/");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyEndpointHasAGateAndAMatrixRowAndEveryRowAnEndpoint() {
		List<String> violations = new ArrayList<>();
		Set<String> endpoints = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : this.handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			boolean ours = handler.getBeanType().getName().startsWith("vn.vibefix.");
			for (String pattern : entry.getKey().getPatternValues()) {
				if (GATED_PREFIXES.stream().noneMatch(pattern::startsWith)) {
					if (ours) {
						violations.add(handler.getShortLogMessage() + " (" + pattern
								+ ") is outside /api and /webhooks, where no gate guards it");
					}
					continue;
				}
				if (!declaresGate(handler)) {
					violations.add(handler.getShortLogMessage() + " (" + pattern + ") declares no @Access gate");
				}
				Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
				if (methods.isEmpty()) {
					violations.add(handler.getShortLogMessage() + " (" + pattern + ") must name its HTTP method");
				}
				methods.forEach(method -> endpoints.add(method.name() + " " + pattern));
			}
		}
		Set<String> rows = new TreeSet<>(MatrixRow.loadMatrix().stream().map(MatrixRow::endpoint).toList());
		violations.addAll(coverageViolations(endpoints, rows));

		assertThat(endpoints).as("gated endpoints").isNotEmpty();
		assertThat(violations).isEmpty();
	}

	@Test
	void coverageCheckFindsMissingAndStaleRows() {
		assertThat(coverageViolations(Set.of("GET /api/v1/a", "POST /api/v1/a"), Set.of("GET /api/v1/a")))
			.containsExactly("POST /api/v1/a has no row in authz/matrix.csv");
		assertThat(coverageViolations(Set.of("GET /api/v1/a"), Set.of("GET /api/v1/a", "GET /api/v1/gone")))
			.containsExactly("authz/matrix.csv has rows for GET /api/v1/gone, which no handler serves");
		assertThat(coverageViolations(Set.of("GET /api/v1/a"), Set.of("GET /api/v1/a"))).isEmpty();
	}

	static List<String> coverageViolations(Set<String> endpoints, Set<String> rows) {
		List<String> violations = new ArrayList<>();
		new TreeSet<>(endpoints).stream()
			.filter(endpoint -> !rows.contains(endpoint))
			.forEach(endpoint -> violations.add(endpoint + " has no row in authz/matrix.csv"));
		new TreeSet<>(rows).stream()
			.filter(row -> !endpoints.contains(row))
			.forEach(row -> violations.add("authz/matrix.csv has rows for " + row + ", which no handler serves"));
		return violations;
	}

	private static boolean declaresGate(HandlerMethod handler) {
		return AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Access.class) != null
				|| AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Access.class) != null;
	}

}
