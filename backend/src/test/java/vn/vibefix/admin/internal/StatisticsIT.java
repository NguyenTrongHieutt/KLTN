package vn.vibefix.admin.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * ADM-9 (FR-62 counts only): every module's figures in their groups, in the fixed order; the
 * private-feedback figure stays out below 5 feedbacks across 3 Mentors.
 */
class StatisticsIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Test
	void theFiguresComeGroupedAndCountsOnly() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, "Vi phạm");
		TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null, null);

		this.mvc.perform(get("/api/v1/admin/statistics").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[0].group").value("usersByRole"))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByRole')].counts[?(@.key == 'CUSTOMER')].value").value(2))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByRole')].counts[?(@.key == 'STUDENT')].value").value(2))
			.andExpect(jsonPath("$.groups[?(@.group == 'studentsByVerification')].counts[?(@.key == 'VERIFIED')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'studentsByVerification')].counts[?(@.key == 'UNVERIFIED')].value")
				.value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'mentorsByApproval')].counts[?(@.key == 'APPROVED')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'lockedAccounts')].counts[?(@.key == 'LOCKED')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'privateFeedback')].counts.length()").value(0))
			.andExpect(jsonPath("$.groups.length()").value(AdminStatisticsController.ORDER.size()));
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
