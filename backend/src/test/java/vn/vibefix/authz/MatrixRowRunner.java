package vn.vibefix.authz;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.ClassUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.authz.Canaries.Canary;

/**
 * Runs one matrix row (AD-20): the fixtures seed a fresh world, exactly one fixture realizes the
 * caller, the request is sent, and the answer must match the row's expectation. Then no canary
 * hidden from the caller may appear anywhere in the response body or headers (SG-55).
 */
public final class MatrixRowRunner {

	/** Where fixtures live; every {@link MatrixFixture} there joins the suite. */
	public static final String FIXTURE_PACKAGE = "vn.vibefix.authz.fixtures";

	static final String BODIES = "authz/bodies/";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MockMvc mvc;

	private final List<MatrixFixture> fixtures;

	private final Canaries canaries;

	public MatrixRowRunner(MockMvc mvc, List<MatrixFixture> fixtures, Canaries canaries) {
		this.mvc = mvc;
		this.fixtures = List.copyOf(fixtures);
		this.canaries = canaries;
	}

	/** Creates every fixture in {@link #FIXTURE_PACKAGE}, autowired from {@code beans}. */
	public static List<MatrixFixture> discoverFixtures(AutowireCapableBeanFactory beans) {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(MatrixFixture.class));
		List<MatrixFixture> fixtures = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents(FIXTURE_PACKAGE)) {
			Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), MatrixRowRunner.class.getClassLoader());
			fixtures.add((MatrixFixture) beans.createBean(type));
		}
		return fixtures;
	}

	public void run(MatrixRow row) throws Exception {
		MatrixWorld world = new MatrixWorld();
		this.fixtures.forEach(fixture -> fixture.seed(world));
		MockHttpServletRequestBuilder request = request(row.method(), world.expand(row.path()))
			.with(realize(row, world));
		if (row.method() != HttpMethod.GET) {
			// A command carries the CSRF token as the SPA sends it (AD-19); without it Spring refuses.
			request.with(csrf());
		}
		if (row.body() != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(world.expand(body(row.body())));
		}
		MockHttpServletResponse response = this.mvc.perform(request).andReturn().getResponse();
		assertExpectation(row, response);
		assertNoHiddenCanary(row, response);
	}

	private RequestPostProcessor realize(MatrixRow row, MatrixWorld world) {
		List<MatrixFixture> realizers = new ArrayList<>();
		RequestPostProcessor realized = null;
		for (MatrixFixture fixture : this.fixtures) {
			Optional<RequestPostProcessor> candidate = fixture.realize(row.caller(), row.state(), world);
			if (candidate.isPresent()) {
				realizers.add(fixture);
				realized = candidate.get();
			}
		}
		if (realizers.isEmpty()) {
			throw new AssertionError("No fixture in " + FIXTURE_PACKAGE + " realizes the caller " + row.caller()
					+ " (row: " + row + ")");
		}
		if (realizers.size() > 1) {
			throw new AssertionError("Several fixtures realize " + row.caller() + ": " + realizers);
		}
		List<String> unknown = row.state()
			.keySet()
			.stream()
			.filter(key -> !realizers.getFirst().stateKeys().contains(key))
			.sorted()
			.toList();
		if (!unknown.isEmpty()) {
			throw new AssertionError("The fixture realizing " + row.caller() + " does not understand subject_state "
					+ unknown + " (row: " + row + ")");
		}
		return realized;
	}

	private static void assertExpectation(MatrixRow row, MockHttpServletResponse response) throws IOException {
		Expectation expect = row.expect();
		if (response.getStatus() != expect.status()) {
			throw new AssertionError("Expected " + expect.status() + " but got " + response.getStatus() + " for " + row);
		}
		if (expect.code() == null) {
			return;
		}
		JsonNode body = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		String code = body.path("code").asString(null);
		if (!expect.code().equals(code)) {
			throw new AssertionError("Expected code " + expect.code() + " but got " + code + " for " + row);
		}
		String reason = body.path("reason").asString(null);
		if (expect.reason() != null && !expect.reason().equals(reason)) {
			throw new AssertionError("Expected reason " + expect.reason() + " but got " + reason + " for " + row);
		}
	}

	private void assertNoHiddenCanary(MatrixRow row, MockHttpServletResponse response) throws IOException {
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		String errorMessage = response.getErrorMessage() == null ? "" : response.getErrorMessage();
		for (Canary canary : this.canaries.hiddenFrom(row.caller())) {
			if (body.contains(canary.value()) || errorMessage.contains(canary.value())) {
				throw new AssertionError("Canary " + canary.name() + " is hidden from " + row.caller()
						+ " but appears in the response body of " + row);
			}
			for (String header : response.getHeaderNames()) {
				for (String value : response.getHeaders(header)) {
					if (header.contains(canary.value()) || value.contains(canary.value())) {
						throw new AssertionError("Canary " + canary.name() + " is hidden from " + row.caller()
								+ " but appears in the response header " + header + " of " + row);
					}
				}
			}
		}
	}

	private static String body(String name) {
		try (InputStream in = MatrixRowRunner.class.getClassLoader().getResourceAsStream(BODIES + name)) {
			if (in == null) {
				throw new AssertionError("No request body " + BODIES + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** For fixture authors: whether {@code state} asks for {@code key=value}. */
	public static boolean has(Map<String, String> state, String key, String value) {
		return value.equals(state.get(key));
	}

}
