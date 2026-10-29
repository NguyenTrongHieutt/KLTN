package vn.vibefix.authz;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.ClassUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.authz.Canaries.Canary;

/**
 * Runs one matrix row (AD-20): the fixtures seed a fresh world, the row's world-binding keys rebind its
 * placeholders ({@link MatrixRow.Binding}), exactly one fixture realizes the caller from the rest of its
 * {@code subject_state}, the request is sent, and the answer must match the row's expectation. Then no canary
 * hidden from the caller may appear anywhere in the response body or headers (SG-55), an amount in its VND form
 * neither ({@link #forms}), except the
 * Crew names on PUB-4's reputation endpoints for the Customer ({@link #exempt}).
 */
public final class MatrixRowRunner {

	/** Where fixtures live; every {@link MatrixFixture} there joins the suite. */
	public static final String FIXTURE_PACKAGE = "vn.vibefix.authz.fixtures";

	static final String BODIES = "authz/bodies/";

	/**
	 * PUB-4's reputation endpoints, public to everyone (Story 21.3): its two histories and the Bid cards' histories in
	 * short stay outside the Customer's Crew-name scan rather than refusing one viewer, which a second Google account
	 * would get around (review 2026-10-27 A-06). Their own suite ({@code PublicReputationIT}) asserts that they name
	 * no Job, Customer, or co-member. PUB-4's identity half is scanned like any other read.
	 */
	static final Set<String> PUBLIC_REPUTATION = Set.of("GET /api/v1/users/{username}/reputation",
			"GET /api/v1/reputation/histories");

	/** The canaries a Customer's Crew-name scan looks for. */
	static final Set<String> CREW_NAMES = Set.of("CREW_NAME", "CROSS_CREW_NAME");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A canary that is an amount in VND, such as {@code CREW_SHARE}. */
	private static final Pattern AMOUNT = Pattern.compile("[0-9]{4,18}");

	private final MockMvc mvc;

	private final List<MatrixFixture> fixtures;

	private final Canaries canaries;

	private final Set<String> publicReputation;

	public MatrixRowRunner(MockMvc mvc, List<MatrixFixture> fixtures, Canaries canaries) {
		this(mvc, fixtures, canaries, PUBLIC_REPUTATION);
	}

	/**
	 * A runner whose Customer's Crew-name exemption covers {@code publicReputation}, for the harness to prove the
	 * exemption on endpoints of its own.
	 */
	MatrixRowRunner(MockMvc mvc, List<MatrixFixture> fixtures, Canaries canaries, Set<String> publicReputation) {
		this.mvc = mvc;
		this.fixtures = List.copyOf(fixtures);
		this.canaries = canaries;
		this.publicReputation = Set.copyOf(publicReputation);
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
		boolean upload = row.body() != null && !row.body().endsWith(".json");
		AbstractMockHttpServletRequestBuilder<?> request = (upload
				? multipart(row.method(), world.expand(row.path(), row.rebound()))
					.file(new MockMultipartFile("file", row.body(), "application/octet-stream", bytes(row.body())))
				: request(row.method(), world.expand(row.path(), row.rebound())))
			.with(realize(row, world));
		if (row.method() != HttpMethod.GET) {
			// A command carries the CSRF token as the SPA sends it (AD-19); without it Spring refuses.
			request.with(csrf());
		}
		if (row.body() != null && !upload) {
			request.contentType(MediaType.APPLICATION_JSON).content(world.expand(body(row.body()), row.rebound()));
		}
		MockHttpServletResponse response = this.mvc.perform(request).andReturn().getResponse();
		assertExpectation(row, response);
		assertNoHiddenCanary(row, response);
	}

	private RequestPostProcessor realize(MatrixRow row, MatrixWorld world) {
		List<MatrixFixture> realizers = new ArrayList<>();
		RequestPostProcessor realized = null;
		for (MatrixFixture fixture : this.fixtures) {
			Optional<RequestPostProcessor> candidate = fixture.realize(row.caller(), row.callerState(), world);
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
		List<String> unknown = row.callerState()
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
			if (exempt(row, canary, this.publicReputation)) {
				continue;
			}
			for (String form : forms(canary)) {
				if (body.contains(form) || errorMessage.contains(form)) {
					throw new AssertionError("Canary " + canary.name() + " is hidden from " + row.caller()
							+ " but appears in the response body of " + row);
				}
				for (String header : response.getHeaderNames()) {
					for (String value : response.getHeaders(header)) {
						if (header.contains(form) || value.contains(form)) {
							throw new AssertionError("Canary " + canary.name() + " is hidden from " + row.caller()
									+ " but appears in the response header " + header + " of " + row);
						}
					}
				}
			}
		}
	}

	/**
	 * The forms a canary may take in an answer: its value and, for an amount, the VND form a screen or an email shows
	 * ({@code %,d} with dots, as {@code PaymentOrders.vnd} formats it; review iteration 4, B9).
	 */
	static List<String> forms(Canary canary) {
		String value = canary.value();
		if (!AMOUNT.matcher(value).matches()) {
			return List.of(value);
		}
		return List.of(value, String.format(Locale.ROOT, "%,d", Long.parseLong(value)).replace(',', '.'));
	}

	/** Whether the row's scan skips the canary: the Customer's Crew names on PUB-4's reputation endpoints. */
	static boolean exempt(MatrixRow row, Canary canary) {
		return exempt(row, canary, PUBLIC_REPUTATION);
	}

	private static boolean exempt(MatrixRow row, Canary canary, Set<String> publicReputation) {
		return row.caller() == MatrixCaller.CUSTOMER && publicReputation.contains(row.endpoint())
				&& CREW_NAMES.contains(canary.name());
	}

	/** The request body file {@code name} under {@value #BODIES}, as text. */
	static String body(String name) {
		return new String(bytes(name), StandardCharsets.UTF_8);
	}

	private static byte[] bytes(String name) {
		try (InputStream in = MatrixRowRunner.class.getClassLoader().getResourceAsStream(BODIES + name)) {
			if (in == null) {
				throw new AssertionError("No request body " + BODIES + name);
			}
			return in.readAllBytes();
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
