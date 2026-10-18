package vn.vibefix.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * AD-23: every error is RFC 9457 Problem Details with a {@code code} from {@link ErrorCode}, plus
 * {@code reason} when given; 5xx has no code, and no English {@code detail} reaches the client.
 * Conventions, Logging: a 5xx carries the request's {@code correlationId}, and its error is logged
 * as one JSON line under the same ID, without request headers.
 */
@WebMvcTest(controllers = ProblemDetailsAdviceTest.ProbeController.class)
@Import(ProblemDetailsAdviceTest.ProbeController.class)
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailsAdviceTest {

	private static final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	private MockMvc mvc;

	@Test
	void codedErrorCarriesCodeAndReason() throws Exception {
		this.mvc.perform(get("/api/v1/probe/missing"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.reason").value("PROBE_GONE"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@ParameterizedTest
	@CsvSource({ "STALE, 409", "JOB_BUSY, 409", "GUARD_FAILED, 422", "RATE_LIMITED, 429", "CONFLICT, 409" })
	void codedErrorUsesTheStatusOfItsCode(ErrorCode code, int expectedStatus) throws Exception {
		this.mvc.perform(get("/api/v1/probe/coded/{code}", code))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.code").value(code.name()))
			.andExpect(jsonPath("$.reason").doesNotExist());
	}

	@Test
	void beanValidationListsFieldsAndConstraints() throws Exception {
		this.mvc
			.perform(post("/api/v1/probe/body").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \" \", \"tags\": [\"a\", \"b\", \"c\", \"d\"]}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors", hasSize(2)))
			.andExpect(jsonPath("$.errors[?(@.field == 'title')].code").value("NotBlank"))
			.andExpect(jsonPath("$.errors[?(@.field == 'tags')].code").value("Size"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void parameterValidationListsParameterAndConstraint() throws Exception {
		this.mvc.perform(get("/api/v1/probe/param").param("size", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[0].field").value("size"))
			.andExpect(jsonPath("$.errors[0].code").value("Min"));
	}

	@Test
	void malformedBodyIsValidation() throws Exception {
		this.mvc.perform(post("/api/v1/probe/body").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void unmappedRouteIsNotFound() throws Exception {
		this.mvc.perform(get("/api/v1/nope"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void unsupportedMethodIsValidation() throws Exception {
		this.mvc.perform(delete("/api/v1/probe/missing"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	@Test
	void unsupportedMediaTypeIsValidation() throws Exception {
		this.mvc.perform(post("/api/v1/probe/body").contentType(MediaType.TEXT_PLAIN).content("x"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	@ParameterizedTest
	@CsvSource({ "400, VALIDATION", "403, FORBIDDEN", "404, NOT_FOUND", "406, VALIDATION", "409, CONFLICT",
			"429, RATE_LIMITED" })
	void springErrorsGetTheCodeOfTheirStatus(int status, String expectedCode) throws Exception {
		this.mvc.perform(get("/api/v1/probe/status/{status}", status))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(expectedCode));
	}

	@Test
	void unexpectedErrorIsA500WithoutCodeOrInternals() throws Exception {
		this.mvc.perform(get("/api/v1/probe/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").doesNotExist())
			.andExpect(jsonPath("$.detail").doesNotExist())
			.andExpect(content().string(not(containsString("secret"))));
	}

	@Test
	void unexpectedErrorCarriesTheCorrelationIdAndIsLoggedAsOneJsonLine(CapturedOutput output) throws Exception {
		String credential = "Bearer probe-credential-" + Long.toHexString(Double.doubleToLongBits(Math.E));
		MvcResult result = this.mvc
			.perform(get("/api/v1/probe/boom").header(HttpHeaders.AUTHORIZATION, credential)
				.header(HttpHeaders.COOKIE, "SESSION=probe-session"))
			.andExpect(status().isInternalServerError())
			.andExpect(header().exists(CorrelationIdFilter.HEADER))
			.andReturn();
		String correlationId = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
		JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));

		assertThat(body.path(ProblemDetailsAdvice.CORRELATION_ID).asString()).isEqualTo(correlationId);
		List<String> lines = output.getOut().lines().filter(line -> line.contains(correlationId)).toList();
		assertThat(lines).singleElement().satisfies(line -> {
			JsonNode logged = json.readTree(line);
			assertThat(logged.path(CorrelationIdFilter.MDC_KEY).asString()).isEqualTo(correlationId);
			assertThat(logged.at("/log/level").asString()).isEqualTo("ERROR");
			assertThat(logged.at("/error/type").asString()).isEqualTo(IllegalStateException.class.getName());
		});
		assertThat(output.getAll()).doesNotContain(credential).doesNotContain("probe-credential")
			.doesNotContain("probe-session");
	}

	@Test
	void springServerErrorsCarryNoCodeButTheCorrelationId() throws Exception {
		MvcResult result = this.mvc.perform(get("/api/v1/probe/status/{status}", 503))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").doesNotExist())
			.andReturn();

		assertThat(json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
			.path(ProblemDetailsAdvice.CORRELATION_ID)
			.asString()).isEqualTo(result.getResponse().getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
	}

	@Test
	void clientErrorsCarryNoCorrelationIdInTheBody() throws Exception {
		this.mvc.perform(get("/api/v1/probe/missing"))
			.andExpect(header().exists(CorrelationIdFilter.HEADER))
			.andExpect(jsonPath("$.correlationId").doesNotExist());
	}

	@Test
	void unauthorizedCarriesNoCode() throws Exception {
		this.mvc.perform(get("/api/v1/probe/status/{status}", 401))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").doesNotExist());
	}

	@Test
	void responseStatusAnnotationKeepsItsStatusAndCode() throws Exception {
		this.mvc.perform(get("/api/v1/probe/annotated"))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void theCodesAreExactlyThoseOfAd23() {
		// The AI codes were removed with every AI feature on 2026-10-18 (sprint change proposal §5.11).
		assertThat(ErrorCode.values()).extracting(Enum::name)
			.containsExactlyInAnyOrder("VALIDATION", "NOT_FOUND", "FORBIDDEN", "STALE", "JOB_BUSY", "GUARD_FAILED",
					"RATE_LIMITED", "CONFLICT");
	}

	@Test
	void everyCodeHasTheStatusOfTheDesign() {
		assertThat(ErrorCode.VALIDATION.status()).isEqualTo(400);
		assertThat(ErrorCode.NOT_FOUND.status()).isEqualTo(404);
		assertThat(ErrorCode.FORBIDDEN.status()).isEqualTo(403);
		assertThat(ErrorCode.STALE.status()).isEqualTo(409);
		assertThat(ErrorCode.JOB_BUSY.status()).isEqualTo(409);
		assertThat(ErrorCode.CONFLICT.status()).isEqualTo(409);
		assertThat(ErrorCode.GUARD_FAILED.status()).isEqualTo(422);
		assertThat(ErrorCode.RATE_LIMITED.status()).isEqualTo(429);
		assertThat(ErrorCode.forStatus(415)).contains(ErrorCode.VALIDATION);
		assertThat(ErrorCode.forStatus(405)).contains(ErrorCode.VALIDATION);
		assertThat(ErrorCode.forStatus(500)).isEmpty();
		assertThat(ErrorCode.forStatus(401)).isEmpty();
		assertThat(ErrorCode.forStatus(200)).isEmpty();
	}

	@ResponseStatus(HttpStatus.CONFLICT)
	static class ProbeConflictException extends RuntimeException {

	}

	enum ProbeReason {

		PROBE_GONE

	}

	record ProbeBody(@NotBlank String title, @Size(max = 3) List<String> tags) {
	}

	/** Endpoints that fail on purpose. Nested in a test class, so only this slice registers it. */
	@RestController
	@RequestMapping("/api/v1/probe")
	static class ProbeController {

		@GetMapping("/missing")
		void missing() {
			throw new ApiException(ErrorCode.NOT_FOUND, ProbeReason.PROBE_GONE);
		}

		@GetMapping("/coded/{code}")
		void coded(@PathVariable ErrorCode code) {
			throw new ApiException(code);
		}

		@PostMapping("/body")
		void body(@Valid @RequestBody ProbeBody body) {
		}

		@GetMapping("/param")
		void param(@RequestParam @Min(1) int size) {
		}

		@GetMapping("/status/{status}")
		void status(@PathVariable int status) {
			throw new ErrorResponseException(HttpStatus.valueOf(status));
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("secret internal detail");
		}

		@GetMapping("/annotated")
		void annotated() {
			throw new ProbeConflictException();
		}

	}

}
