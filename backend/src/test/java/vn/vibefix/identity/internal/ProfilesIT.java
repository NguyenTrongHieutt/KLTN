package vn.vibefix.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.ContactVisibility;
import vn.vibefix.notification.RecipientAddresses;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;

/** FR-3, FR-4, FR-51, UX-DR5, AD-20: profiles, the theme, and who reads a Customer's contact. */
class ProfilesIT extends IntegrationTest {

	private static final String CUSTOMER = """
			{"kind":"BUSINESS","displayName":"Tiệm bánh Lan","contactEmail":"lan@tiembanh.vn","phone":"+84 912 345 678"}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ContactVisibility contacts;

	@Autowired
	private RecipientAddresses addresses;

	@Test
	void aCustomerSavesAndReadsTheirProfile() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);

		this.mvc.perform(put("/api/v1/profile/customer").with(as(lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(CUSTOMER)).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/profile").with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("CUSTOMER"))
			.andExpect(jsonPath("$.githubLogin").value(Matchers.startsWith("user-")))
			.andExpect(jsonPath("$.customer.kind").value("BUSINESS"))
			.andExpect(jsonPath("$.customer.displayName").value("Tiệm bánh Lan"))
			.andExpect(jsonPath("$.customer.phone").value("+84 912 345 678"))
			.andExpect(jsonPath("$.student").doesNotExist());
		assertThat(this.addresses.emailAddress(lan)).as("the contact email takes over").contains("lan@tiembanh.vn");
	}

	@Test
	void invalidFieldsAnswerValidationByFieldName() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);

		this.mvc.perform(put("/api/v1/profile/customer").with(as(lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"kind":"INDIVIDUAL","displayName":" ","contactEmail":"not-an-email","phone":"1234567"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("displayName", "contactEmail", "phone")));
	}

	@Test
	void aProfileOfAnotherRoleDoesNotExistForTheCaller() throws Exception {
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);

		this.mvc.perform(put("/api/v1/profile/customer").with(as(minh, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(CUSTOMER)).andExpect(status().isNotFound());
	}

	@Test
	void aStudentShowsRepositoriesAsReferencesAndHidesTheirNameAndSchoolByDefault() throws Exception {
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);

		this.mvc.perform(put("/api/v1/profile/student").with(as(minh, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"realName":"Trần Minh","school":"ĐH Bách khoa","yearOfStudy":3,
					 "linkedinUrl":"https://www.linkedin.com/in/tran-minh","showNameAndSchool":false,
					 "repositories":["https://github.com/minh/shop-app","Minh/Shop-App.git","minh/blog"]}
					"""))
			.andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/profile").with(as(minh, Role.STUDENT)))
			.andExpect(jsonPath("$.student.repositories").value(Matchers.contains("minh/shop-app", "minh/blog")))
			.andExpect(jsonPath("$.student.showNameAndSchool").value(false))
			.andExpect(jsonPath("$.student.yearOfStudy").value(3));
		assertThat(this.jdbc.sql("SELECT show_name_and_school FROM identity.student_profile").query(Boolean.class).single())
			.isFalse();
	}

	@Test
	void aSixthOrAForeignRepositoryIsRefused() throws Exception {
		UUID minh = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);

		for (String repositories : new String[] { "[\"a/1\",\"a/2\",\"a/3\",\"a/4\",\"a/5\",\"a/6\"]",
				"[\"https://evil.example/a/b\"]", "[\"a/b/c\"]" }) {
			this.mvc.perform(put("/api/v1/profile/student").with(as(minh, Role.STUDENT)).with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"showNameAndSchool\":true,\"repositories\":" + repositories + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
		}
	}

	@Test
	void theThemeIsSavedToTheAccountAndDefaultsToTheSystem() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/session").with(as(lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.account.theme").value("SYSTEM"));

		this.mvc.perform(put("/api/v1/account/theme").with(as(lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"theme\":\"DARK\"}")).andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/session").with(as(lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.account.theme").value("DARK"));
	}

	@Test
	void theContactIsReadByTheCustomerAnAdministratorAndAGrantedMentorOnly() throws Exception {
		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		UUID mentor = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null, null);
		UUID student = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.mvc.perform(put("/api/v1/profile/customer").with(as(lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(CUSTOMER)).andExpect(status().isNoContent());
		String contact = "/api/v1/customers/" + lan + "/contact";

		this.mvc.perform(get(contact).with(as(lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contactEmail").value("lan@tiembanh.vn"));
		this.mvc.perform(get(contact).with(as(admin, Role.ADMINISTRATOR))).andExpect(status().isOk());
		this.mvc.perform(get(contact).with(as(other, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get(contact).with(as(student, Role.STUDENT))).andExpect(status().isNotFound());
		this.mvc.perform(get(contact).with(as(mentor, Role.MENTOR))).andExpect(status().isNotFound());

		UUID job = UUID.randomUUID();
		this.contacts.grant(lan, mentor, job);
		this.contacts.grant(lan, mentor, job);
		this.mvc.perform(get(contact).with(as(mentor, Role.MENTOR))).andExpect(status().isOk());

		this.contacts.end(lan, mentor, job, this.clock.instant());
		this.mvc.perform(get(contact).with(as(mentor, Role.MENTOR))).andExpect(status().isNotFound());
	}

	private static RequestPostProcessor as(UUID account, Role role) {
		return authentication(IdentityTestSupport.authentication(account, role));
	}

}
