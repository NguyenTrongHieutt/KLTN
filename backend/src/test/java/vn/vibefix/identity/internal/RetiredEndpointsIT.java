package vn.vibefix.identity.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;

/**
 * Story 19.7: MEN-5, ADM-13, ADM-3, and the student card upload are gone; no handler answers them, so
 * even the callers they once served find nothing.
 */
class RetiredEndpointsIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Test
	void noHandlerAnswersTheRetiredEndpoints() throws Exception {
		RequestPostProcessor mentor = as(IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null), Role.MENTOR);
		RequestPostProcessor student = as(IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null), Role.STUDENT);
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		RequestPostProcessor admin = as(ha.accountId(), Role.ADMINISTRATOR);

		this.mvc.perform(get("/api/v1/profile/mentor").with(mentor)).andExpect(status().isNotFound());
		this.mvc.perform(put("/api/v1/profile/mentor").with(mentor).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/mentor-applications").with(admin)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/mentor-applications/" + UUID.randomUUID()).with(admin))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/admin/student-cards").with(admin)).andExpect(status().isNotFound());
		this.mvc.perform(multipart("/api/v1/verification/student-card")
			.file(new MockMultipartFile("file", "the.pdf", "application/pdf", TestFiles.pdf()))
			.with(student)
			.with(csrf())).andExpect(status().isNotFound());
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
