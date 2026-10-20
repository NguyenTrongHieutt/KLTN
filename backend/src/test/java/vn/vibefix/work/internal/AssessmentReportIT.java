package vn.vibefix.work.internal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
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

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-8, FR-10, FR-11, AD-18: the Assessment Report starts with the Product's open Findings, each as its
 * type, file, and masked value only, never the commit; FR-10's nine flaws are marked Đã kiểm tra,
 * Phát hiện, or Không áp dụng; recommendations are numbered; a typed body is kept as typed. JOB-1 gives
 * the Mentor and the Crew of an Assessment Job the public Source Repository, and the Customer nothing.
 */
class AssessmentReportIT extends IntegrationTest {

	private static final String COMMIT = "9f".repeat(20);

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
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
	}

	@Test
	void theReportListsTheOpenFindingsWithTheirMaskedValuesOnly() throws Exception {
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		UUID run = scanRun(product);
		finding(product, run, "CREDENTIAL", "Khóa bí mật Stripe", "src/pay.js", "sk_l••••••••Q7mZ", null, "OPEN");
		finding(product, run, "PERSONAL_DATA", "Số điện thoại khách hàng", "data/khach.csv", "09•• ••• •12, 09•• ••• •34", 120,
				"OPEN");
		finding(product, run, "CREDENTIAL", "Khóa GitHub đã đổi", "old.env", "ghp_••••••••abcd", null, "RESOLVED");
		UUID job = job(product, "ASSESSMENT");

		create(job, this.vy, Role.STUDENT, "").andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(Matchers.containsString(
					"- **Khóa bí mật Stripe** trong `src/pay.js`: `sk_l••••••••Q7mZ`. Cách xử lý đề nghị:")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString(
					"- **Số điện thoại khách hàng** trong `data/khach.csv` (120 dòng): `09•• ••• •12, 09•• ••• •34`.")))
			.andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString("old.env"))))
			.andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString(COMMIT))))
			.andExpect(jsonPath("$.body").value(Matchers.not(Matchers.containsString("{openFindings}"))))
			.andExpect(jsonPath("$.body").value(Matchers.containsString(
					"**Đã kiểm tra**, **Phát hiện**, hoặc **Không áp dụng**")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("theo số và phiên bản báo cáo")))
			.andExpect(jsonPath("$.body").value(Matchers.containsString("## Giải thích thuật ngữ")))
			.andExpect(jsonPath("$.visibility").value("CUSTOMER"));
	}

	@Test
	void withoutOpenFindingsTheSectionSaysSoAndATypedBodyIsKept() throws Exception {
		UUID clean = job(WorkTestSupport.product(this.jdbc, this.lan, "Sổ sạch"), "ASSESSMENT");
		create(clean, this.hung, Role.MENTOR, "").andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(Matchers.containsString("Không có vấn đề nào còn mở khi tạo báo cáo.")));

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

	private UUID scanRun(UUID product) {
		UUID run = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO scanning.scan_run (id, product_id, customer_account_id, product_name, repository_full_name,
				    repository_github_id, purpose, status, rules_version, found_count, new_findings, requested_at, finished_at)
				VALUES (:id, :product, :owner, 'Sổ', 'lan/so-dat-ban', 1, 'INTAKE', 'SUCCEEDED', 2, 3, 3, :now, :now)
				""").param("id", run).param("product", product).param("owner", this.lan).param("now", now).update();
		return run;
	}

	private void finding(UUID product, UUID run, String category, String type, String path, String masked, Integer lines,
			String status) {
		Timestamp now = Timestamp.from(this.clock.instant());
		boolean open = "OPEN".equals(status);
		this.jdbc.sql("""
				INSERT INTO scanning.finding (id, product_id, scan_run_id, category, rule_id, rule_type, file_path,
				    commit_sha, committed_at, masked_preview, match_count, fingerprint, status, resolution, resolved_at,
				    created_at)
				VALUES (:id, :product, :run, :category, 'rule', :type, :path, :commit, :now, :masked, :lines,
				    md5(random()::text) || md5(random()::text), :status, :resolution, :resolved, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("product", product)
			.param("run", run)
			.param("category", category)
			.param("type", type)
			.param("path", path)
			.param("commit", COMMIT)
			.param("masked", masked)
			.param("lines", lines)
			.param("status", status)
			.param("resolution", open ? null : "ROTATED")
			.param("resolved", open ? null : now)
			.param("now", now)
			.update();
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
