package vn.vibefix.work.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestPeople;

/**
 * FR-10, FR-11: the Assessment Report starts from its skeleton, with nothing from a scan (vibefix scans no
 * code, Story 23.1); FR-10's nine flaws are marked Đã kiểm tra, Phát hiện, or Không áp dụng; recommendations
 * are numbered; a typed body is kept as typed. JOB-1 gives
 * the Mentor and the Crew of an Assessment Job the public Source Repository, and the Customer nothing.
 */
class AssessmentReportIT extends IntegrationTest {

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

	@BeforeEach
	void people() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TestPeople.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TestPeople.REACT), "React");
	}

	@Test
	void theReportStartsFromItsSkeletonWithNothingFromAScan() throws Exception {
		UUID job = job(WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây"), "ASSESSMENT");

		// vibefix scans no code (Story 23.1): the Report has no open Findings section and nothing pre-filled.
		create(job, this.vy, Role.STUDENT, "").andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString("{openFindings}"))))
			.andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString("Vấn đề còn mở từ kiểm tra"))))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("## Những gì còn thiếu")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString(
					"**Đã kiểm tra**, **Phát hiện**, hoặc **Không áp dụng**")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("theo số và phiên bản báo cáo")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("## Giải thích thuật ngữ")))
			.andExpect(jsonPath("$.visibility").value("CUSTOMER"));
	}

	@Test
	void aTypedBodyIsKept() throws Exception {
		UUID typed = job(WorkTestSupport.product(this.jdbc, this.lan, "Sổ tự viết"), "ASSESSMENT");
		create(typed, this.hung, Role.MENTOR, "## Hiện trạng\\n\\nTự viết.").andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value("## Hiện trạng\n\nTự viết."));
	}

	@Test
	void jobOneLinksTheMentorAndTheCrewToTheSourceRepositoryOnAnAssessmentJobOnly() throws Exception {
		UUID assessment = job(WorkTestSupport.product(this.jdbc, this.lan, "Sổ đánh giá"), "ASSESSMENT");
		for (RequestPostProcessor team : List.of(as(this.hung, Role.MENTOR), as(this.vy, Role.STUDENT))) {
			this.mvc.perform(get("/api/v1/boards/" + assessment + "/progress").with(team))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assessment").value(true))
				.andExpect(jsonPath("$.sourceRepositoryUrl").value("https://github.com/lan/so-dat-ban"));
		}
		this.mvc.perform(get("/api/v1/boards/" + assessment + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assessment").value(false))
			.andExpect(jsonPath("$.sourceRepositoryUrl").doesNotExist());

		UUID bugFix = job(WorkTestSupport.product(this.jdbc, this.lan, "Sổ sửa lỗi"), "BUG_FIX");
		this.mvc.perform(get("/api/v1/boards/" + bugFix + "/progress").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.assessment").value(false))
			.andExpect(jsonPath("$.sourceRepositoryUrl").doesNotExist());
	}

	private UUID job(UUID product, String type) {
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, List.of(UUID.randomUUID()),
				List.of(List.of(UUID.randomUUID())), this.clock.instant(), type);
		this.boards.seed(job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy " + job);
		WorkTestSupport.crewRow(this.jdbc, job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		return job;
	}

	private ResultActions create(UUID job, UUID account, Role role, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + job + "/pages").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Báo cáo đánh giá\",\"body\":\"" + body + "\",\"template\":\"ASSESSMENT_REPORT\"}"));
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
