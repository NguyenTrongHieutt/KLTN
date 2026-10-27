package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.sun.net.httpserver.HttpServer;

import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * FR-75, FR-17, AD-30, AD-41 (Story 11.1): with {@code features.largeCrews} on, up to five Students share one
 * Crew, each with a share above 0 that only they see, and FR-17's Crew condition waits for every one of them.
 */
@TestPropertySource(properties = "vibefix.features.large-crews=true")
class LargeCrewIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final Map<String, String> CONTRIBUTORS = new ConcurrentHashMap<>();

	private static final HttpServer GITHUB = start();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	private UUID lan;

	private UUID hung;

	private UUID team;

	private UUID vy;

	private UUID job;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void aJobInAgreement() throws Exception {
		CONTRIBUTORS.clear();
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "200 [{\"id\":987654321,\"login\":\"lan\"}]");
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		UUID product = product(this.lan);
		this.job = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", this.job).param("product", product).param("owner", this.lan)
			.param("now", Timestamp.from(now)).param("lapses", Timestamp.from(now.plus(Duration.ofDays(14)))).update();
		this.hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		this.team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", this.team).param("mentor", this.hung).update();
		this.vy = member("Lê Thảo Vy", true);
		UUID application = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
				    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
				VALUES (:id, :job, :product, :lan, :hung, 'SUBMITTED', 800000, 7, 'Cách làm', '1.0', :now)
				""")
			.param("id", application).param("job", this.job).param("product", product).param("lan", this.lan)
			.param("hung", this.hung).param("now", Timestamp.from(now)).update();
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/applications/" + application + "/select")
			.with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}")).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/nda").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"ndaVersion\":\"1.0\",\"commitEmailNoticeAcknowledged\":true}")).andExpect(status().isNoContent());
	}

	@Test
	void fiveStudentsShareTheJobAndFR17WaitsForEachOfThem() throws Exception {
		List<UUID> crew = new ArrayList<>(List.of(this.vy));
		for (String name : List.of("Bùi Gia Huy", "Trần Quang Minh", "Phạm Ngọc Ánh", "Đặng Hải Nam")) {
			crew.add(member(name, true));
		}
		long crewVersion = 0;
		for (UUID student : crew) {
			add(student, crewVersion).andExpect(status().isOk());
			crewVersion++;
		}
		UUID sixth = member("Võ Minh Khôi", true);
		add(sixth, crewVersion).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_LIMIT"));
		assertThat(count("SELECT count(*) FROM team.crew_reservation")).isEqualTo(5);

		sharedTerms(crewVersion, crew, 40, List.of(12, 12, 12, 12, 0)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SHARES_INVALID"));
		sharedTerms(crewVersion, crew, 40, List.of(12, 12, 12, 12, 12)).andExpect(status().isOk())
			.andExpect(jsonPath("$.latest.versionNo").value(1));
		this.mvc.perform(get("/api/v1/crew/assignments").with(as(crew.get(1), Role.STUDENT)))
			.andExpect(jsonPath("$[0].sharePercent").value(12));

		for (UUID student : crew.subList(0, 4)) {
			confirm(student, 1, true).andExpect(status().isNoContent());
		}
		assertThat(openTodos("job.pick_crew", this.hung)).as("FR-17 waits for the fifth").isOne();
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.largeCrews").value(true))
			.andExpect(jsonPath("$.crewConfirmed").value(false));
		confirm(crew.get(4), 1, true).andExpect(status().isNoContent());
		assertThat(openTodos("job.pick_crew", this.hung)).isZero();
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.crewConfirmed").value(true));
	}

	private ResultActions sharedTerms(long crewVersion, List<UUID> crew, int mentor, List<Integer> shares) throws Exception {
		StringBuilder parties = new StringBuilder("{\"accountId\":\"" + this.hung + "\",\"percent\":" + mentor + "}");
		for (int index = 0; index < crew.size(); index++) {
			parties.append(",{\"accountId\":\"").append(crew.get(index)).append("\",\"percent\":").append(shares.get(index))
				.append('}');
		}
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/crew/terms").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewVersion\":" + crewVersion + ",\"shares\":[" + parties + "]}"));
	}

	private ResultActions add(UUID student, long crewVersion) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentId\":\"" + student + "\",\"crewVersion\":" + crewVersion + "}"));
	}

	private ResultActions terms(long crewVersion, int mentor, int student) throws Exception {
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/crew/terms").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"crewVersion":%d,"shares":[{"accountId":"%s","percent":%d},{"accountId":"%s","percent":%d}]}
					""".formatted(crewVersion, this.hung, mentor, this.vy, student)));
	}

	private ResultActions confirm(UUID student, int version, boolean publicCode) throws Exception {
		return this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/confirm").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewTermsVersionNo\":%d,\"ndaVersion\":\"1.0\",\"publicCodeAcknowledged\":%s,\"commitEmailNoticeAcknowledged\":true}"
				.formatted(version, publicCode)));
	}

	private UUID member(String name, boolean verified) {
		UUID student = TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				verified ? LocalDate.parse("2026-09-30") : null);
		this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID()).param("team", this.team).param("student", student).update();
		return student;
	}

	private long openTodos(String key, UUID account) {
		return count("SELECT count(*) FROM notification.todo WHERE todo_key = '" + key + "' AND account_id = '" + account
				+ "' AND closed_at IS NULL");
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id).param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).param("now", now).update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				String answer = CONTRIBUTORS.getOrDefault(exchange.getRequestURI().getPath(), "404 {}");
				int status = Integer.parseInt(answer.substring(0, 3));
				byte[] body = answer.substring(4).getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
