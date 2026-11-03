package vn.vibefix.authz;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * FR-75, FR-79, AD-20 (Story 22.4): the Team Evidence Package's Crew Terms read is gone with the Crew Terms, so anyone,
 * signed in or not, gets the {@code 404} of any unknown path. It left {@code authz/matrix.csv} with its controller, so
 * this is what keeps it from coming back under the old address.
 */
class RetiredCrewTermsPathsIT extends IntegrationTest {

	private static final String OLD_PATH = "/api/v1/admin/jobs/00000000-0000-7000-8000-00000000f001/crew-terms";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void theOldPathIsUnknownToEveryCaller() throws Exception {
		UUID user = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-" + UUID.randomUUID(), "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, user);
		UUID administrator = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);

		expectNotFound(get(OLD_PATH));
		expectNotFound(get(OLD_PATH).with(signedIn(user, null)));
		expectNotFound(get(OLD_PATH).with(signedIn(administrator, Role.ADMINISTRATOR)));
	}

	private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
		this.mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private RequestPostProcessor signedIn(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
