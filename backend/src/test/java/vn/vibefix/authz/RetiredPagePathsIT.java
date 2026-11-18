package vn.vibefix.authz;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Story 24.1 (D2, FR-29, FR-30, FR-31, FR-70): the Documentation Space is retired, so every address JOB-3 and
 * "Chia sẻ tài liệu cũ" answered on is gone with its controllers -- the Page tree and one Page, its versions and
 * a restore, its visibility, the share checklist and the shared Pages. Each of them now answers the {@code 404} of
 * any unknown path, to anyone, signed in or not, with a write carrying its CSRF token so nothing is refused for
 * the wrong reason. They left {@code authz/matrix.csv} with their controllers, so this is what keeps them from
 * coming back under the old addresses.
 */
class RetiredPagePathsIT extends IntegrationTest {

	private static final String BOARD = "00000000-0000-7000-8000-000000240f01";

	private static final String PAGE = "00000000-0000-7000-8000-000000240f02";

	private static final List<Supplier<MockHttpServletRequestBuilder>> RETIRED = List.of(
			() -> get("/api/v1/boards/" + BOARD + "/pages"),
			() -> post("/api/v1/boards/" + BOARD + "/pages").content("{\"title\":\"Trang\",\"body\":\"\"}"),
			() -> get("/api/v1/pages/" + PAGE),
			() -> put("/api/v1/pages/" + PAGE).content("{\"title\":\"Trang\",\"body\":\"\",\"version\":1}"),
			() -> get("/api/v1/pages/" + PAGE + "/versions"), () -> get("/api/v1/pages/" + PAGE + "/versions/1"),
			() -> post("/api/v1/pages/" + PAGE + "/versions/1/restore").content("{\"version\":1}"),
			() -> put("/api/v1/pages/" + PAGE + "/visibility").content("{\"visibility\":\"CUSTOMER\"}"),
			() -> get("/api/v1/jobs/" + BOARD + "/page-shares"),
			() -> put("/api/v1/jobs/" + BOARD + "/page-shares").content("{\"sourceJobIds\":[]}"),
			() -> get("/api/v1/jobs/" + BOARD + "/shared-pages"));

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void everyOldPageAddressIsUnknownToEveryCaller() throws Exception {
		UUID user = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-" + UUID.randomUUID(), "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, user);
		UUID administrator = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);

		for (Supplier<MockHttpServletRequestBuilder> retired : RETIRED) {
			expectNotFound(retired.get());
			expectNotFound(retired.get().with(signedIn(user, null)));
			expectNotFound(retired.get().with(signedIn(administrator, Role.ADMINISTRATOR)));
		}
	}

	private void expectNotFound(MockHttpServletRequestBuilder request) throws Exception {
		this.mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private RequestPostProcessor signedIn(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
