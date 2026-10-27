package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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

import com.jayway.jsonpath.JsonPath;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 17.1 (FR-70, AD-41, UX-DR59): the Customer shares earlier Jobs of the same Product with a new Job, an
 * Assessment Report first and nothing unchosen; the new Job's Mentor and ACTIVE Crew are told and read the
 * customer-visible Pages only, read-only and without history, until the new Job is Closed; an ASSIGNED or
 * FORMER member, a locked one included, reads nothing.
 */
class PageSharesIT extends IntegrationTest {

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

	private UUID mai;

	private UUID vy;

	private UUID assessment;

	private UUID bugFix;

	private UUID elsewhere;

	private UUID next;

	private String report;

	private String team;

	@BeforeEach
	void aProductWithEarlierJobsAndANewOne() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		this.mai = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Mai", List.of(TeamTestSupport.REACT),
				"React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		Instant now = this.clock.instant();
		this.bugFix = job(product, this.hung, "BUG_FIX", now.minus(Duration.ofDays(30)));
		visible(id(create(this.bugFix, "Cách sửa lỗi trùng bàn", null)));
		close(this.bugFix);
		this.assessment = job(product, this.hung, "ASSESSMENT", now.minus(Duration.ofDays(20)));
		this.report = id(create(this.assessment, "Báo cáo đánh giá", "ASSESSMENT_REPORT"));
		this.team = id(create(this.assessment, "Ghi chú nội bộ", null));
		close(this.assessment);
		UUID quiet = job(product, this.hung, "BUG_FIX", now.minus(Duration.ofDays(10)));
		create(quiet, "Chỉ nhóm", null).andExpect(status().isCreated());
		close(quiet);
		this.elsewhere = job(WorkTestSupport.product(this.jdbc, this.lan, "Cửa hàng hoa"), this.hung, "BUG_FIX", now);
		visible(id(create(this.elsewhere, "Tài liệu cửa hàng", null)));

		this.next = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.mai, List.of(UUID.randomUUID()),
				List.of(List.of(UUID.randomUUID())), now, "BUG_FIX");
		this.jdbc.sql("UPDATE job.job SET state = 'AGREEMENT', title = 'Thêm đặt cọc' WHERE id = :id").param("id", this.next).update();
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, this.next, this.vy, "ACTIVE", now.minus(Duration.ofMinutes(5)));
	}

	@Test
	void theChecklistOffersTheReportFirstAndSharingTellsTheMentorAndTheActiveCrew() throws Exception {
		UUID assigned = WorkTestSupport.student(this.jdbc, "Lê Minh Khoa");
		WorkTestSupport.crewRow(this.jdbc, this.next, assigned, "ASSIGNED", null);

		this.mvc.perform(get("/api/v1/jobs/" + this.next + "/page-shares").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.editable").value(true))
			.andExpect(jsonPath("$.jobs.length()").value(2))
			.andExpect(jsonPath("$.jobs[0].jobId").value(this.assessment.toString()))
			.andExpect(jsonPath("$.jobs[0].assessmentReport").value(true))
			.andExpect(jsonPath("$.jobs[0].pages").value(1))
			.andExpect(jsonPath("$.jobs[0].shared").value(false))
			.andExpect(jsonPath("$.jobs[1].jobId").value(this.bugFix.toString()))
			.andExpect(jsonPath("$.jobs[1].shared").value(false));
		this.mvc.perform(get("/api/v1/jobs/" + this.next + "/page-shares").with(as(this.mai, Role.MENTOR)))
			.andExpect(status().isNotFound());
		UUID stranger = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.next + "/page-shares").with(as(stranger, Role.CUSTOMER)))
			.andExpect(status().isNotFound());

		share(this.assessment).andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs[0].shared").value(true))
			.andExpect(jsonPath("$.jobs[1].shared").value(false));
		assertThat(told(this.mai)).isOne();
		assertThat(told(this.vy)).isOne();
		assertThat(told(assigned)).isZero();
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'work.pages_shared'")).isOne();
		share(this.assessment).andExpect(status().isOk());
		assertThat(told(this.mai)).as("nothing new, nobody told again").isOne();

		share(this.elsewhere).andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("NOT_SHAREABLE"));
		share().andExpect(status().isOk()).andExpect(jsonPath("$.jobs[0].shared").value(false));
		assertThat(count("SELECT count(*) FROM audit.audit_entry WHERE action = 'work.pages_unshared'")).isOne();

		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", this.next).update();
		this.mvc.perform(get("/api/v1/jobs/" + this.next + "/page-shares").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.editable").value(false));
		share(this.assessment).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("SHARING_CLOSED"));
	}

	@Test
	void onlyTheMentorAndTheActiveCrewReadTheCustomerVisiblePagesUntilTheJobIsClosed() throws Exception {
		Instant now = this.clock.instant();
		UUID assigned = WorkTestSupport.student(this.jdbc, "Lê Minh Khoa");
		WorkTestSupport.crewRow(this.jdbc, this.next, assigned, "ASSIGNED", null);
		// Story 20.4: a lock counts as leaving, and an unlock brings nothing back.
		UUID left = WorkTestSupport.student(this.jdbc, "Ngô Bảo Ngọc");
		WorkTestSupport.crewRow(this.jdbc, this.next, left, "ACTIVE", now.minus(Duration.ofDays(2)));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', window_end = :now, released_at = :now, released_reason = 'LEFT'
				WHERE member_account_id = :student
				""").param("now", java.sql.Timestamp.from(now.minus(Duration.ofDays(1)))).param("student", left).update();
		UUID former = WorkTestSupport.student(this.jdbc, "Võ Hoài Nam");
		WorkTestSupport.crewRow(this.jdbc, this.next, former, "ACTIVE", now.minus(Duration.ofDays(2)));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', window_end = :now, released_at = :now, released_reason = 'REMOVED'
				WHERE member_account_id = :student
				""").param("now", java.sql.Timestamp.from(now.minus(Duration.ofDays(1)))).param("student", former).update();
		share(this.assessment).andExpect(status().isOk());

		for (UUID reader : List.of(this.mai, this.vy)) {
			Role role = reader.equals(this.mai) ? Role.MENTOR : Role.STUDENT;
			this.mvc.perform(get("/api/v1/jobs/" + this.next + "/shared-pages").with(as(reader, role)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].jobId").value(this.assessment.toString()))
				.andExpect(jsonPath("$[0].pages.length()").value(1))
				.andExpect(jsonPath("$[0].pages[0].id").value(this.report));
			this.mvc.perform(get("/api/v1/pages/" + this.report).with(as(reader, role)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shared").value(true))
				.andExpect(jsonPath("$.readOnly").value(true));
			this.mvc.perform(get("/api/v1/pages/" + this.team).with(as(reader, role))).andExpect(status().isNotFound());
			this.mvc.perform(get("/api/v1/pages/" + this.report + "/versions").with(as(reader, role)))
				.andExpect(status().isNotFound());
			this.mvc.perform(get("/api/v1/boards/" + this.assessment + "/work-items").with(as(reader, role)))
				.andExpect(status().isNotFound());
		}
		for (UUID outsider : List.of(assigned, left, former)) {
			this.mvc.perform(get("/api/v1/jobs/" + this.next + "/shared-pages").with(as(outsider, Role.STUDENT)))
				.andExpect(status().isNotFound());
			this.mvc.perform(get("/api/v1/pages/" + this.report).with(as(outsider, Role.STUDENT)))
				.andExpect(status().isNotFound());
		}
		this.mvc.perform(get("/api/v1/pages/" + this.team).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.shared").value(false));

		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED' WHERE id = :id").param("id", this.next).update();
		this.mvc.perform(get("/api/v1/jobs/" + this.next + "/shared-pages").with(as(this.mai, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		this.mvc.perform(get("/api/v1/pages/" + this.report).with(as(this.mai, Role.MENTOR))).andExpect(status().isNotFound());
	}

	/** An Active Job with a Board, for {@code mentor} to write Pages in; closed later where the test says. */
	private UUID job(UUID product, UUID mentor, String type, Instant created) {
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, mentor, List.of(UUID.randomUUID()),
				List.of(List.of(UUID.randomUUID())), created, type);
		this.boards.seed(job, true);
		return job;
	}

	private void close(UUID job) {
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED' WHERE id = :id").param("id", job).update();
	}

	private void visible(String page) throws Exception {
		this.mvc.perform(put("/api/v1/pages/" + page + "/visibility").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"CUSTOMER\"}")).andExpect(status().isOk());
	}

	private ResultActions create(UUID job, String title, String template) throws Exception {
		String body = "{\"title\":\"" + title + "\",\"body\":\"\"" + (template == null ? "" : ",\"template\":\"" + template + "\"") + "}";
		return this.mvc.perform(post("/api/v1/boards/" + job + "/pages").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions share(UUID... jobs) throws Exception {
		String ids = List.of(jobs).stream().map(job -> "\"" + job + "\"").collect(Collectors.joining(","));
		return this.mvc.perform(put("/api/v1/jobs/" + this.next + "/page-shares").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"jobIds\":[" + ids + "]}"));
	}

	private long told(UUID account) {
		return count("SELECT count(*) FROM notification.notification WHERE type = 'work.pages_shared' AND account_id = '"
				+ account + "'");
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private static String id(ResultActions created) throws Exception {
		return JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
