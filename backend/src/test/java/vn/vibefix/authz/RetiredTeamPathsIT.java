package vn.vibefix.authz;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-6, FR-14, FR-51 (Story 21.7): the 13 endpoints of the retired Teams are unknown paths, so anyone, signed in or not,
 * gets the {@code 404} of any unknown path. They left {@code authz/matrix.csv} with their controllers, so this is what
 * keeps them from coming back under the old addresses.
 */
class RetiredTeamPathsIT extends IntegrationTest {

	private static final String ID = "00000000-0000-7000-8000-00000000d001";

	private static final List<String> OLD_ENDPOINTS = List.of("GET /api/v1/mentor/team", "GET /api/v1/mentor/team/students",
			"GET /api/v1/mentor/team/students/" + ID, "POST /api/v1/mentor/team/invitations",
			"POST /api/v1/mentor/team/join-requests/" + ID + "/accept",
			"POST /api/v1/mentor/team/join-requests/" + ID + "/decline", "DELETE /api/v1/mentor/team/members/" + ID,
			"GET /api/v1/team", "GET /api/v1/team/mentors", "POST /api/v1/team/join-requests",
			"POST /api/v1/team/invitations/" + ID + "/accept", "POST /api/v1/team/invitations/" + ID + "/decline",
			"DELETE /api/v1/team/membership");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void everyOldEndpointIsAnUnknownPathToAnonymousCallers() throws Exception {
		for (String endpoint : OLD_ENDPOINTS) {
			expectNotFound(call(endpoint));
		}
	}

	@Test
	void everyOldEndpointIsAnUnknownPathToAUserAndAnAdministrator() throws Exception {
		UUID user = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-" + UUID.randomUUID(), "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, user);
		UUID administrator = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		for (String endpoint : OLD_ENDPOINTS) {
			expectNotFound(call(endpoint).with(signedIn(user, null)));
			expectNotFound(call(endpoint).with(signedIn(administrator, Role.ADMINISTRATOR)));
		}
	}

	private MockHttpServletRequestBuilder call(String endpoint) {
		String[] parts = endpoint.split(" ", 2);
		return request(HttpMethod.valueOf(parts[0]), parts[1]).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}");
	}

	private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
		this.mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private RequestPostProcessor signedIn(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
