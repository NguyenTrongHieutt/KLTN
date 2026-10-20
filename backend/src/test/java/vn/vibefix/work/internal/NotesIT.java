package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import vn.vibefix.support.PostgresTestDatabase;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-47, FR-76, AD-39, UX-DR79: notes pass FR-47 and are appended to the history with their author and
 * time; nothing edits or deletes one, and the runtime role cannot; "Sửa lại" adds a new note quoting
 * one of the author's own; notes, Attachments, submissions, and returns read as one time-ordered history.
 */
class NotesIT extends IntegrationTest {

	private static final UUID MILESTONE = UUID.randomUUID();

	private static final UUID CRITERION = UUID.randomUUID();

	private static final String ALL = "{\"meetsCriteria\":true,\"tested\":true,\"docsUpdated\":true,\"noSecrets\":true}";

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

	private UUID story;

	@BeforeEach
	void aSeededStoryAssignedToVyInProgress() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		UUID job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, List.of(MILESTONE),
				List.of(List.of(CRITERION)), this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', 1500000, 2, now(), 'FUNDED', now(), 1)
				""").param("id", MILESTONE).param("job", job).update();
		this.boards.seed(job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		this.story = this.jdbc.sql("SELECT id FROM work.work_item WHERE criterion_id = :c").param("c", CRITERION)
			.query(UUID.class).single();
		this.mvc.perform(put("/api/v1/work-items/" + this.story).with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"type\":\"STORY\",\"title\":\"Tiêu chí 1.1\",\"description\":\"Cách kiểm tra 1.1\",\"milestoneId\":\""
					+ MILESTONE + "\",\"assigneeId\":\"" + this.vy + "\",\"version\":1}"))
			.andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/work-items/" + this.story + "/move").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"IN_PROGRESS\",\"version\":2}"))
			.andExpect(status().isOk());
	}

	@Test
	void aNoteIsAppendedWithItsAuthorAndACorrectionQuotesTheEarlierOne() throws Exception {
		String body = note(this.vy, Role.STUDENT, "Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.", null, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.history[-1].action").value("NOTE"))
			.andExpect(jsonPath("$.history[-1].actor").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.history[-1].mine").value(true))
			.andExpect(jsonPath("$.history[-1].comment").value("Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu."))
			.andExpect(jsonPath("$.item.version").value(3))
			.andReturn().getResponse().getContentAsString();
		String first = JsonPath.read(body, "$.history[-1].id");
		this.mvc.perform(get("/api/v1/work-items/" + this.story).with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.history[-1].mine").value(false));

		note(this.hung, Role.MENTOR, "Sửa của người khác", first, null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_AUTHOR"));
		note(this.vy, Role.STUDENT, "Sửa", UUID.randomUUID().toString(), null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("QUOTE_UNKNOWN"));
		note(this.vy, Role.STUDENT, "   ", null, null).andExpect(status().isBadRequest());
		note(this.vy, Role.STUDENT, "Sửa: quy tắc nằm trong tệp 0007, kiểm thử đang viết.", first, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.history[-1].quoted.id").value(first))
			.andExpect(jsonPath("$.history[-1].quoted.actor").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.history[-1].quoted.text").value("Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu."))
			.andExpect(jsonPath("$.history[-1].comment").value("Sửa: quy tắc nằm trong tệp 0007, kiểm thử đang viết."));
		assertThat(this.jdbc.sql("SELECT comment FROM work.work_item_history WHERE id = :id")
			.param("id", UUID.fromString(first)).query(String.class).single())
			.isEqualTo("Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.");

		note(this.lan, Role.CUSTOMER, "Khách ghi chú", null, null).andExpect(status().isNotFound());
	}

	@Test
	void aNoteWithACredentialIsRefusedUntilMasked() throws Exception {
		note(this.vy, Role.STUDENT, "Khóa là " + fakeToken(), null, null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"))
			.andExpect(jsonPath("$.matches[0].kind").value("CREDENTIAL"));
		note(this.vy, Role.STUDENT, "Khóa là " + fakeToken(), null, "MASK").andExpect(status().isCreated());
		String stored = this.jdbc.sql("SELECT comment FROM work.work_item_history WHERE action = 'NOTE' AND work_item_id = :id")
			.param("id", this.story).query(String.class).single();
		assertThat(stored).startsWith("Khóa là ").doesNotContain(fakeToken());
	}

	@Test
	void notesAttachmentsAndReviewsReadAsOneHistoryThatNobodyRewrites() throws Exception {
		note(this.vy, Role.STUDENT, "Bắt đầu sửa", null, null).andExpect(status().isCreated());
		this.mvc.perform(post("/api/v1/work-items/" + this.story + "/links").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://github.com/lan/so-dat-ban/pull/3\"}"))
			.andExpect(status().isCreated());
		this.mvc.perform(post("/api/v1/work-items/" + this.story + "/submit").with(as(this.vy, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"selfCheck\":" + ALL + ",\"note\":\"Xong\"}"))
			.andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/work-items/" + this.story + "/review").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":4,\"decision\":\"RETURN\",\"comment\":\"Hãy thêm kiểm thử\"}"))
			.andExpect(status().isOk());
		note(this.hung, Role.MENTOR, "Kiểm thử cho trường hợp xóa đơn", null, null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.history[*].action").value(org.hamcrest.Matchers.contains("SEEDED", "UPDATED", "MOVED",
					"NOTE", "ATTACHED", "SUBMITTED", "RETURNED", "NOTE")));

		Set<String> privileges = new TreeSet<>(this.jdbc.sql("""
				SELECT privilege_type FROM information_schema.role_table_grants
				WHERE grantee = :grantee AND table_schema = 'work' AND table_name = 'work_item_history'
				""").param("grantee", PostgresTestDatabase.APP).query(String.class).list());
		assertThat(privileges).doesNotContain("UPDATE", "TRUNCATE");
		assertThatThrownBy(() -> this.jdbc.sql("UPDATE work.work_item_history SET comment = 'đã sửa'").update()).rootCause()
			.hasMessageContaining("permission denied for table work_item_history");
		assertThatThrownBy(() -> this.jdbc.sql("UPDATE work.attachment SET url = 'https://khac.test'").update()).rootCause()
			.hasMessageContaining("permission denied for table attachment");
	}

	private ResultActions note(UUID account, Role role, String text, String quotes, String sensitive) throws Exception {
		String json = "{\"text\":\"" + text + "\"" + (quotes == null ? "" : ",\"quotes\":\"" + quotes + "\"")
				+ (sensitive == null ? "" : ",\"sensitive\":\"" + sensitive + "\"") + "}";
		return this.mvc.perform(post("/api/v1/work-items/" + this.story + "/notes").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
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
