package vn.vibefix.shared.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import vn.vibefix.support.IntegrationTest;

/**
 * AD-23, Conventions, Logging: an error raised outside a controller reaches Boot's {@code /error},
 * which answers the same Problem Details shape: the status's code, and on a 5xx the request's
 * correlation ID; never Boot's message, exception, or trace.
 */
class ProblemErrorAttributesIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void aServerErrorOutsideAControllerCarriesTheCorrelationIdAndNoDetail() throws Exception {
		MvcResult result = this.mvc
			.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
				.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/probe")
				.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret detail")))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.status").value(500))
			.andExpect(jsonPath("$.code").doesNotExist())
			.andExpect(jsonPath("$.correlationId").value(Matchers.matchesPattern("[0-9a-f-]{36}")))
			.andExpect(jsonPath("$.message").doesNotExist())
			.andExpect(jsonPath("$.trace").doesNotExist())
			.andExpect(jsonPath("$.exception").doesNotExist())
			.andReturn();

		org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
			.contains(result.getResponse().getHeader("X-Correlation-Id"))
			.doesNotContain("secret detail");
	}

	@Test
	void aClientErrorOutsideAControllerCarriesItsCode() throws Exception {
		this.mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.correlationId").doesNotExist());
	}

}
