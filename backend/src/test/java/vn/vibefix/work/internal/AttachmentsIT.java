package vn.vibefix.work.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.JobUploads;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-47, FR-76, NFR-3, AD-21, AD-34, AD-39, AD-41: an active member attaches files and {@code https}
 * links, each with its author and time and an {@code ATTACHED} history entry; the Mentor and the Crew
 * read and download them, the Customer never; a text file and a link pass FR-47; files count with the
 * screenshots toward the Job's 200 MB; only the author removes one, never once the Work Item is Done,
 * and the history keeps the removal; a former member sees only what was there in their window.
 */
class AttachmentsIT extends IntegrationTest {

	private static final UUID MILESTONE = UUID.randomUUID();

	private static final UUID CRITERION = UUID.randomUUID();

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

	private UUID story;

	@BeforeEach
	void aSeededStoryOnAnActiveJobWithVyOnTheCrew() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng",
				List.of(TeamTestSupport.REACT), "React");
		UUID product = WorkTestSupport.product(this.jdbc, this.lan, "Sổ đặt bàn Quán Mây");
		this.job = WorkTestSupport.activeJob(this.jdbc, product, this.lan, this.hung, List.of(MILESTONE),
				List.of(List.of(CRITERION)), this.clock.instant());
		this.boards.seed(this.job, true);
		this.vy = WorkTestSupport.student(this.jdbc, "Trần Thảo Vy");
		WorkTestSupport.crewRow(this.jdbc, this.job, this.vy, "ACTIVE", this.clock.instant().minus(Duration.ofMinutes(5)));
		this.story = this.jdbc.sql("SELECT id FROM work.work_item WHERE criterion_id = :c").param("c", CRITERION)
			.query(UUID.class).single();
	}

	@Test
	void aMemberAttachesAFileAndALinkThatTheCrewReadsAndTheCustomerNever() throws Exception {
		String body = upload(this.vy, Role.STUDENT, "ke-hoach.pdf", "application/pdf", TestFiles.pdf(), null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[0].kind").value("FILE"))
			.andExpect(jsonPath("$.attachments[0].name").value("ke-hoach.pdf"))
			.andExpect(jsonPath("$.attachments[0].size").value(TestFiles.pdf().length))
			.andExpect(jsonPath("$.attachments[0].author").value("Trần Thảo Vy"))
			.andExpect(jsonPath("$.attachments[0].removable").value(true))
			.andExpect(jsonPath("$.history[-1].action").value("ATTACHED"))
			.andExpect(jsonPath("$.history[-1].attachment").value("ke-hoach.pdf"))
			.andExpect(jsonPath("$.item.version").value(1))
			.andReturn().getResponse().getContentAsString();
		String download = JsonPath.read(body, "$.attachments[0].downloadUrl");
		link(this.vy, "https://github.com/lan/so-dat-ban/pull/3", "", null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[1].kind").value("LINK"))
			.andExpect(jsonPath("$.attachments[1].name").value("github.com"))
			.andExpect(jsonPath("$.attachments[1].domain").value("github.com"))
			.andExpect(jsonPath("$.attachments[1].url").value("https://github.com/lan/so-dat-ban/pull/3"));
		link(this.vy, "https://docs.google.com/document/d/abc", "Kế hoạch kiểm thử", null)
			.andExpect(jsonPath("$.attachments[2].name").value("Kế hoạch kiểm thử"))
			.andExpect(jsonPath("$.attachments[2].domain").value("docs.google.com"));

		this.mvc.perform(get("/api/v1/work-items/" + this.story).with(as(this.hung, Role.MENTOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(3))
			.andExpect(jsonPath("$.attachments[0].removable").value(false));
		this.mvc.perform(get(download).with(as(this.hung, Role.MENTOR))).andExpect(status().isOk());
		this.mvc.perform(get(download).with(as(this.vy, Role.STUDENT))).andExpect(status().isOk());
		this.mvc.perform(get(download).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/work-items/" + this.story).with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/progress").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("ke-hoach.pdf",
					"github.com", "Kế hoạch kiểm thử"));
	}

	@Test
	void aLinkMustBeHttpsWithoutUserInfoOrASecret() throws Exception {
		for (String url : List.of("http://github.com/lan", "https://", "javascript:alert(1)",
				"https://lan:matkhau@github.com/lan", "https://github.com/a b")) {
			link(this.vy, url, "", null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("LINK_INVALID"));
		}
		link(this.vy, "https://example.test/?token=" + fakeToken(), "", "MASK").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("LINK_SENSITIVE"))
			.andExpect(jsonPath("$.matches[0].kind").value("CREDENTIAL"));
		link(this.vy, "https://github.com/lan", "Khóa " + fakeToken(), null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"));
		link(this.vy, "https://github.com/lan", "Khóa " + fakeToken(), "MASK").andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[0].name").value(org.hamcrest.Matchers.not(
					org.hamcrest.Matchers.containsString(fakeToken()))));
		assertThat(attachments()).isOne();
	}

	@Test
	void aTextFileWithATokenIsRefusedUntilMaskedAndTheStoredFileHoldsNoToken() throws Exception {
		byte[] content = ("TEN_QUAN=Quán Mây\nGITHUB=" + fakeToken() + "\n").getBytes(StandardCharsets.UTF_8);
		upload(this.vy, Role.STUDENT, "cau-hinh.txt", "text/plain", content, null).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SENSITIVE_CONTENT"))
			.andExpect(jsonPath("$.matches[0].kind").value("CREDENTIAL"));
		assertThat(attachments()).isZero();
		String body = upload(this.vy, Role.STUDENT, "cau-hinh.txt", "text/plain", content, "MASK")
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String download = JsonPath.read(body, "$.attachments[0].downloadUrl");
		String stored = this.mvc.perform(get(download).with(as(this.hung, Role.MENTOR))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(stored).contains("TEN_QUAN=Quán Mây").doesNotContain(fakeToken());
	}

	@Test
	void theJobsScreenshotsAndAttachmentsShareItsTwoHundredMegabytes() throws Exception {
		this.jdbc.sql("""
				INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
				VALUES (:job, :file, 'loi.png', :size, now())
				""").param("job", this.job).param("file", UUID.randomUUID()).param("size", JobUploads.QUOTA_BYTES - 10)
			.update();
		upload(this.vy, Role.STUDENT, "ke-hoach.pdf", "application/pdf", TestFiles.pdf(), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("JOB_FILE_QUOTA"));
		upload(this.vy, Role.STUDENT, "trang.svg", "image/svg+xml", TestFiles.svg(), null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("FILE_TYPE_NOT_ALLOWED"));
		assertThat(attachments()).isZero();
	}

	@Test
	void onlyTheAuthorRemovesAnAttachmentAndNeverOnceTheWorkItemIsDone() throws Exception {
		String body = upload(this.vy, Role.STUDENT, "ke-hoach.pdf", "application/pdf", TestFiles.pdf(), null)
			.andReturn().getResponse().getContentAsString();
		String first = JsonPath.read(body, "$.attachments[0].id");
		String download = JsonPath.read(body, "$.attachments[0].downloadUrl");
		remove(this.hung, Role.MENTOR, first).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("NOT_AUTHOR"));
		remove(this.vy, Role.STUDENT, first).andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(0))
			.andExpect(jsonPath("$.history[-1].action").value("DETACHED"))
			.andExpect(jsonPath("$.history[-1].attachment").value("ke-hoach.pdf"));
		remove(this.vy, Role.STUDENT, first).andExpect(status().isNotFound());
		this.mvc.perform(get(download).with(as(this.vy, Role.STUDENT))).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT count(*) FROM work.attachment WHERE id = :id AND removed_by = :vy")
			.param("id", UUID.fromString(first)).param("vy", this.vy).query(Long.class).single()).isOne();

		String second = JsonPath.read(link(this.vy, "https://github.com/lan/so-dat-ban/pull/3", "", null)
			.andReturn().getResponse().getContentAsString(), "$.attachments[0].id");
		this.jdbc.sql("UPDATE work.work_item SET status = 'DONE' WHERE id = :id").param("id", this.story).update();
		this.mvc.perform(get("/api/v1/work-items/" + this.story).with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$.attachments[0].removable").value(false));
		remove(this.vy, Role.STUDENT, second).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("WORK_ITEM_DONE"));
	}

	@Test
	void aFormerMemberSeesOnlyWhatWasThereWhenTheirWindowEnded() throws Exception {
		String body = upload(this.vy, Role.STUDENT, "ke-hoach.pdf", "application/pdf", TestFiles.pdf(), null)
			.andReturn().getResponse().getContentAsString();
		String download = JsonPath.read(body, "$.attachments[0].downloadUrl");
		this.clock.advance(Duration.ofHours(1));
		this.jdbc.sql("UPDATE job.crew_row SET status = 'INACTIVE', window_end = :end WHERE student_account_id = :vy")
			.param("end", Timestamp.from(this.clock.instant()))
			.param("vy", this.vy)
			.update();
		this.clock.advance(Duration.ofHours(1));
		link(this.hung, "https://github.com/lan/so-dat-ban/pull/4", "Bản sửa sau", null).andExpect(status().isCreated());

		this.mvc.perform(get("/api/v1/work-items/" + this.story).with(as(this.vy, Role.STUDENT)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(1))
			.andExpect(jsonPath("$.attachments[0].name").value("ke-hoach.pdf"))
			.andExpect(jsonPath("$.attachments[0].removable").value(false))
			.andExpect(jsonPath("$.history[?(@.attachment == 'Bản sửa sau')]").isEmpty());
		this.mvc.perform(get(download).with(as(this.vy, Role.STUDENT))).andExpect(status().isOk());
		upload(this.vy, Role.STUDENT, "moi.pdf", "application/pdf", TestFiles.pdf(), null).andExpect(status().isNotFound());
	}

	private ResultActions upload(UUID account, Role role, String name, String type, byte[] content, String sensitive)
			throws Exception {
		var request = multipart("/api/v1/work-items/" + this.story + "/attachments")
			.file(new MockMultipartFile("file", name, type, content))
			.with(as(account, role))
			.with(csrf());
		if (sensitive != null) {
			request.param("sensitive", sensitive);
		}
		return this.mvc.perform(request);
	}

	private ResultActions link(UUID account, String url, String label, String sensitive) throws Exception {
		Role role = account.equals(this.hung) ? Role.MENTOR : Role.STUDENT;
		String json = "{\"url\":\"" + url + "\",\"label\":\"" + label + "\""
				+ (sensitive == null ? "" : ",\"sensitive\":\"" + sensitive + "\"") + "}";
		return this.mvc.perform(post("/api/v1/work-items/" + this.story + "/links").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions remove(UUID account, Role role, String attachment) throws Exception {
		return this.mvc.perform(delete("/api/v1/work-items/" + this.story + "/attachments/" + attachment)
			.with(as(account, role))
			.with(csrf()));
	}

	private long attachments() {
		return this.jdbc.sql("SELECT count(*) FROM work.attachment WHERE work_item_id = :id").param("id", this.story)
			.query(Long.class).single();
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
