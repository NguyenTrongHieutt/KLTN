package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-10, FR-30, FR-31, AD-20: Pages start from templates with a badge; the Assessment Report is offered
 * only on an Assessment Job, once, and stays customer-visible; only the Mentor switches visibility; the
 * Customer reads customer-visible Pages only, at their current version, and never the Board.
 */
class PageVisibilityIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private Boards boards;

	private UUID lan;

	private UUID hung;

	private UUID vy;

	private UUID job(String type) {
		this.lan = this.lan != null ? this.lan : IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = this.hung != null ? this.hung
				: TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
						"React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây " + type);
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, List.of(UUID.randomUUID()),
				List.of(List.of(UUID.randomUUID())), this.clock.instant(), type);
		this.boards.seed(job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy " + type);
		WorkTestSupport.crewRow(this.jdbc, job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		return job;
	}

	@Test
	void aTemplateFillsABlankPageAndTheReportIsOnlyForAnAssessmentJobOnce() throws Exception {
		UUID bugFix = job("BUG_FIX");
		this.mvc.perform(get("/api/v1/boards/" + bugFix + "/pages").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.templates.length()").value(5))
			.andExpect(jsonPath("$.templates[*].template").value(Matchers.not(Matchers.hasItem("ASSESSMENT_REPORT"))));
		create(bugFix, this.vy, Role.STUDENT, "Biên bản họp tuần 1", "MEETING_NOTES").andExpect(status().isCreated())
			.andExpect(jsonPath("$.template").value("MEETING_NOTES"))
			.andExpect(jsonPath("$.visibility").value("TEAM"))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("## Nội dung đã bàn")));
		create(bugFix, this.hung, Role.MENTOR, "Báo cáo", "ASSESSMENT_REPORT").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("TEMPLATE_NOT_AVAILABLE"));

		UUID assessment = job("ASSESSMENT");
		this.mvc.perform(get("/api/v1/boards/" + assessment + "/pages").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.templates[0].template").value("ASSESSMENT_REPORT"))
			.andExpect(jsonPath("$.templates[0].title").value("Báo cáo đánh giá"));
		create(assessment, this.vy, Role.STUDENT, "Báo cáo đánh giá", "ASSESSMENT_REPORT").andExpect(status().isCreated())
			.andExpect(jsonPath("$.visibility").value("CUSTOMER"))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("## Những gì còn thiếu")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("Triển khai và vận hành")))
			.andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString("phần mềm độc hại"))));
		create(assessment, this.hung, Role.MENTOR, "Báo cáo thứ hai", "ASSESSMENT_REPORT").andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("REPORT_EXISTS"));
		this.mvc.perform(get("/api/v1/boards/" + assessment + "/pages").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.templates.length()").value(5));
	}

	@Test
	void onlyTheMentorSwitchesVisibilityAndTheReportStaysVisible() throws Exception {
		UUID assessment = job("ASSESSMENT");
		String notes = id(create(assessment, this.vy, Role.STUDENT, "Ghi chú", null));
		String report = id(create(assessment, this.hung, Role.MENTOR, "Báo cáo đánh giá", "ASSESSMENT_REPORT"));

		visibility(this.vy, Role.STUDENT, notes, "CUSTOMER").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		visibility(this.hung, Role.MENTOR, notes, "CUSTOMER").andExpect(status().isOk())
			.andExpect(jsonPath("$.visibility").value("CUSTOMER"))
			.andExpect(jsonPath("$.versionNo").value(1));
		visibility(this.hung, Role.MENTOR, report, "TEAM").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("REPORT_ALWAYS_VISIBLE"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'work.page_visibility_changed'")
			.query(Long.class).single()).isOne();
		visibility(this.hung, Role.MENTOR, notes, "TEAM").andExpect(jsonPath("$.visibility").value("TEAM"));
	}

	@Test
	void theCustomerReadsOnlyCustomerVisiblePagesAtTheirCurrentVersion() throws Exception {
		UUID assessment = job("ASSESSMENT");
		String team = id(create(assessment, this.vy, Role.STUDENT, "Ghi chú nội bộ", null));
		String report = id(create(assessment, this.vy, Role.STUDENT, "Báo cáo đánh giá", "ASSESSMENT_REPORT"));
		this.mvc.perform(put("/api/v1/pages/" + report).with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"## Hiện trạng\\nỨng dụng chạy tốt.\",\"version\":1}"))
			.andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/boards/" + assessment + "/pages").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customer").value(true))
			.andExpect(jsonPath("$.readOnly").value(true))
			.andExpect(jsonPath("$.templates.length()").value(0))
			.andExpect(jsonPath("$.pages.length()").value(1))
			.andExpect(jsonPath("$.pages[0].id").value(report));
		this.mvc.perform(get("/api/v1/pages/" + report).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(2))
			.andExpect(jsonPath("$.body").value("## Hiện trạng\nỨng dụng chạy tốt."));
		this.mvc.perform(get("/api/v1/pages/" + team).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/pages/" + report + "/versions").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		create(assessment, this.lan, Role.CUSTOMER, "Của khách", null).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + assessment + "/work-items").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", assessment).update();
		this.mvc.perform(get("/api/v1/pages/" + report).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isOk());
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.mvc.perform(get("/api/v1/pages/" + report).with(as(other, Role.CUSTOMER))).andExpect(status().isNotFound());
	}

	private ResultActions create(UUID job, UUID account, Role role, String title, String template) throws Exception {
		String body = "{\"title\":\"" + title + "\",\"body\":\"\"" + (template == null ? "" : ",\"template\":\"" + template + "\"") + "}";
		return this.mvc.perform(post("/api/v1/boards/" + job + "/pages").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions visibility(UUID account, Role role, String page, String visibility) throws Exception {
		return this.mvc.perform(put("/api/v1/pages/" + page + "/visibility").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"" + visibility + "\"}"));
	}

	private static String id(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
