package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-29, FR-47, AD-20, AD-23, AD-41: the Mentor and the active Crew write Pages in a two-level tree, each
 * save and restore a version on the version it was made on; a long body is checked in chunks; a former
 * member reads only the Pages of their window as they stood; nobody else finds the Documentation Space.
 */
class PagesIT extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

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

	private UUID job;

	@BeforeEach
	void anActiveJobWithItsCrew() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		this.job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, List.of(UUID.randomUUID()),
				List.of(List.of(UUID.randomUUID())), this.clock.instant());
		this.boards.seed(this.job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
	}

	@Test
	void everySaveAndRestoreIsAVersionMadeOnTheCurrentOne() throws Exception {
		String id = id(create(this.vy, Role.STUDENT, "Ghi chú kỹ thuật", "# Cách chạy\nnpm install", null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.versionNo").value(1))
			.andExpect(jsonPath("$.author").value("Trần Thảo Vy")));
		save(this.hung, Role.MENTOR, id, "Ghi chú kỹ thuật", "# Cách chạy\nnpm ci", null, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(2))
			.andExpect(jsonPath("$.author").value("Đỗ Mạnh Hùng"));
		save(this.vy, Role.STUDENT, id, "Ghi chú", "Cũ", null, 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));

		this.mvc.perform(get("/api/v1/pages/" + id + "/versions").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].versionNo").value(2))
			.andExpect(jsonPath("$[1].author").value("Trần Thảo Vy"));
		this.mvc.perform(get("/api/v1/pages/" + id + "/versions/1").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.body").value("# Cách chạy\nnpm install"));
		this.mvc.perform(post("/api/v1/pages/" + id + "/versions/1/restore").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(3))
			.andExpect(jsonPath("$.restoredFrom").value(1))
			.andExpect(jsonPath("$.body").value("# Cách chạy\nnpm install"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM work.page_version WHERE page_id = :id").param("id", UUID.fromString(id))
			.query(Long.class).single()).isEqualTo(3);
	}

	@Test
	void theTreeHasTwoLevels() throws Exception {
		String top = id(create(this.hung, Role.MENTOR, "Hướng dẫn", "", null));
		String child = id(create(this.hung, Role.MENTOR, "Cài đặt", "", top).andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentId").value(top)));
		create(this.hung, Role.MENTOR, "Quá sâu", "", child).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PAGE_TOO_DEEP"));
		String other = id(create(this.hung, Role.MENTOR, "Quyết định", "", null));
		save(this.hung, Role.MENTOR, top, "Hướng dẫn", "", other, 1).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PAGE_TOO_DEEP"));
		create(this.hung, Role.MENTOR, "Lạc", "", UUID.randomUUID().toString()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PAGE_PARENT_UNKNOWN"));
		save(this.hung, Role.MENTOR, child, "Cài đặt", "", other, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.parentId").value(other));

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/pages").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readOnly").value(false))
			.andExpect(jsonPath("$.pages[*].title").value(org.hamcrest.Matchers.contains("Hướng dẫn", "Quyết định", "Cài đặt")));
	}

	@Test
	void aLongBodyIsCheckedInChunksBeforeItIsStored() throws Exception {
		String token = fakeToken();
		String body = ("Dòng ghi chú bình thường số mười.\n").repeat(200) + "Khóa thử: " + token + "\n";
		assertThat(body.length()).isGreaterThan(4000);
		create(this.vy, Role.STUDENT, "Ghi chú dài", body, null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"));
		String saved = create(this.vy, Role.STUDENT, "Ghi chú dài", body, null, "MASK").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		assertThat(saved).doesNotContain(token).contains("Dòng ghi chú bình thường số mười.");
	}

	@Test
	void aFormerMemberReadsOnlyThePagesOfTheirWindowAsTheyStood() throws Exception {
		String mine = id(create(this.vy, Role.STUDENT, "Ghi chú của Vy", "Bản của Vy", null));
		this.clock.advance(Duration.ofHours(1));
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', window_end = :now, released_at = :now, released_reason = 'REMOVED'
				WHERE student_account_id = :vy
				""").param("now", Timestamp.from(this.clock.instant())).param("vy", this.vy).update();
		this.clock.advance(Duration.ofHours(1));
		save(this.hung, Role.MENTOR, mine, "Ghi chú đã sửa", "Bản của Hùng", null, 1).andExpect(status().isOk());
		String later = id(create(this.hung, Role.MENTOR, "Trang sau khi Vy rời", "", null));

		this.mvc.perform(get("/api/v1/boards/" + this.job + "/pages").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.readOnly").value(true))
			.andExpect(jsonPath("$.pages.length()").value(1))
			.andExpect(jsonPath("$.pages[0].title").value("Ghi chú của Vy"));
		this.mvc.perform(get("/api/v1/pages/" + mine).with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.body").value("Bản của Vy"))
			.andExpect(jsonPath("$.versionNo").value(1))
			.andExpect(jsonPath("$.readOnly").value(true));
		this.mvc.perform(get("/api/v1/pages/" + mine + "/versions").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.length()").value(1));
		this.mvc.perform(get("/api/v1/pages/" + mine + "/versions/2").with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/pages/" + later).with(as(this.vy, Role.STUDENT))).andExpect(status().isNotFound());
		save(this.vy, Role.STUDENT, mine, "Vy sửa", "", null, 2).andExpect(status().isNotFound());
	}

	@Test
	void nobodyElseFindsTheTeamOnlyPagesAndTheSpaceIsReadOnlyOutsideActive() throws Exception {
		String id = id(create(this.hung, Role.MENTOR, "Kế hoạch", "", null));
		UUID tung = WorkTestSupport.student(this.jdbc, "Lê Minh Tùng");
		WorkTestSupport.crewRow(this.jdbc, this.job, tung, "ASSIGNED", null);
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		for (RequestPostProcessor outsider : List.of(as(tung, Role.STUDENT), as(admin, Role.ADMINISTRATOR))) {
			this.mvc.perform(get("/api/v1/boards/" + this.job + "/pages").with(outsider)).andExpect(status().isNotFound());
			this.mvc.perform(get("/api/v1/pages/" + id).with(outsider)).andExpect(status().isNotFound());
		}
		// The Job's Customer reads only customer-visible Pages (Story 5.6): this one is team-only.
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/pages").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pages.length()").value(0));
		this.mvc.perform(get("/api/v1/pages/" + id).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSING' WHERE id = :id").param("id", this.job).update();
		create(this.hung, Role.MENTOR, "Muộn", "", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.reason").value("BOARD_READ_ONLY"));
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/pages").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.readOnly").value(true));
	}

	private ResultActions create(UUID account, Role role, String title, String body, String parent) throws Exception {
		return create(account, role, title, body, parent, null);
	}

	private ResultActions create(UUID account, Role role, String title, String body, String parent, String sensitive)
			throws Exception {
		return this.mvc.perform(post("/api/v1/boards/" + this.job + "/pages").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(request(title, body, parent, null, sensitive)));
	}

	private ResultActions save(UUID account, Role role, String id, String title, String body, String parent, int version)
			throws Exception {
		return this.mvc.perform(put("/api/v1/pages/" + id).with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(request(title, body, parent, version, null)));
	}

	private static String request(String title, String body, String parent, Integer version, String sensitive) {
		java.util.Map<String, Object> request = new java.util.LinkedHashMap<>();
		request.put("title", title);
		request.put("body", body);
		request.put("parentId", parent);
		request.put("version", version);
		request.put("sensitive", sensitive);
		return JSON.writeValueAsString(request);
	}

	private static String id(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
	}

	/** A GitHub token shape built at run time, so no credential-shaped literal sits in the source. */
	private static String fakeToken() {
		String alphabet = "Q7mZ2xK9pL4vB8nR3tY6wC1dF5gH0jSaEuIoNcXbVk";
		StringBuilder token = new StringBuilder("ghp").append('_');
		for (int i = 0; i < 36; i++) {
			token.append(alphabet.charAt((i * 7 + 3) % alphabet.length()));
		}
		return token.toString();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
