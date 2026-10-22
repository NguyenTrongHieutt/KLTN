package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.Crews;
import vn.vibefix.job.Crews.Relationship;
import vn.vibefix.job.internal.JobLifecycle.Actor;
import vn.vibefix.job.internal.JobLifecycle.Applied;
import vn.vibefix.shared.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.Teams;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Story 11.3 (FR-75, AD-41 "Tier 2 changes"), with {@code features.largeCrews} on: on an Active Job whose
 * Crew is Vy and Tùng under version 1, the Mentor swaps Tùng for Bảo Anh. The change waits for every party
 * and for no Milestone to be Held, takes effect at the last confirmation or inside M5, and J8 voids one
 * still waiting. The Customer sees only the Crew in force until it takes effect.
 */
@TestPropertySource(properties = "vibefix.features.large-crews=true")
class CrewChangeIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final HttpServer GITHUB = start();

	private static final UUID M1 = UUID.randomUUID();

	private static final UUID M2 = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private Teams teams;

	@Autowired
	private Crews crews;

	@Autowired
	private JobLifecycle lifecycle;

	@Autowired
	private PlatformTransactionManager transactions;

	private UUID lan;

	private UUID hung;

	private UUID team;

	private UUID vy;

	private UUID tung;

	private UUID anh;

	private UUID job;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void anActiveJobWithVyAndTung() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		this.hung = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		this.team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", this.team).param("mentor", this.hung).update();
		this.vy = member("Lê Thảo Vy");
		this.tung = member("Phạm Hải Tùng");
		this.anh = member("Ngô Bảo Anh");
		Instant now = this.clock.instant();
		Instant earlier = now.minus(Duration.ofDays(3));
		this.job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, agreement_version_no, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :earlier, :lapses, :mentor, :application, :earlier, 1, 7,
				    :earlier, :earlier)
				""")
			.param("id", this.job).param("product", product(this.lan)).param("owner", this.lan)
			.param("earlier", Timestamp.from(earlier)).param("lapses", Timestamp.from(earlier.plus(Duration.ofDays(14))))
			.param("mentor", this.hung).param("application", UUID.randomUUID()).update();
		String content = """
				{"scope":"Sửa lỗi","priceVnd":3000000,"codeJob":false,"assessment":false,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Giai đoạn 1","priceVnd":1500000,"dueOn":"2026-11-20","revisionRounds":2,
				 "criteria":[]},{"id":"%s","title":"Giai đoạn 2","priceVnd":1500000,"dueOn":"2026-11-30","revisionRounds":2,
				 "criteria":[]}]}
				""".formatted(M1, M2);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :at)
				""").param("job", this.job).param("content", content).param("mentor", this.hung)
			.param("at", Timestamp.from(earlier)).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :at, :mentor, :at)
				""").param("job", this.job).param("content", content).param("owner", this.lan).param("mentor", this.hung)
			.param("at", Timestamp.from(earlier)).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    accepted_at, crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', 1500000, 2, :at, 'ACCEPTED', :at, :at, 1)
				""").param("id", M1).param("job", this.job).param("at", Timestamp.from(earlier)).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				VALUES (:id, :job, 1, 'Giai đoạn 2', 1500000, 2, :at)
				""").param("id", M2).param("job", this.job).param("at", Timestamp.from(earlier)).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 40, :mentor, :at)
				""").param("job", this.job).param("mentor", this.hung).param("at", Timestamp.from(earlier)).update();
		share(this.hung, null, 40);
		for (UUID student : List.of(this.vy, this.tung)) {
			UUID row = UUID.randomUUID();
			this.jdbc.sql("""
					INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
					VALUES (:id, :job, :student, 'ACTIVE', :at, :at, :at)
					""").param("id", row).param("job", this.job).param("student", student).param("at", Timestamp.from(earlier))
				.update();
			new TransactionTemplate(this.transactions)
				.executeWithoutResult(status -> this.teams.reserveForCrew(row, this.hung, student, this.job));
			share(student, row, 30);
			this.jdbc.sql("""
					INSERT INTO job.crew_terms_confirmation (job_id, version_no, crew_row_id, confirmed_at) VALUES (:job, 1, :row, :at)
					""").param("job", this.job).param("row", row).param("at", Timestamp.from(earlier)).update();
			this.jdbc.sql("""
					INSERT INTO matching.nda_signature (job_id, account_id, nda_version, signed_at) VALUES (:job, :id, '1.0', :at)
					""").param("job", this.job).param("id", student).param("at", Timestamp.from(earlier)).update();
		}
	}

	@Test
	void aSwapTakesEffectAtTheLastConfirmationAndTheCustomerLearnsOnlyThen() throws Exception {
		long jobVersion = jobVersion();
		change(crewVersion(), 40, Map.of(this.vy, 30, this.anh, 30)).andExpect(status().isOk())
			.andExpect(jsonPath("$.change.waitingVersionNo").value(2))
			.andExpect(jsonPath("$.change.inForceVersionNo").value(1))
			.andExpect(jsonPath("$.change.confirmed").value(0))
			.andExpect(jsonPath("$.change.total").value(2))
			.andExpect(jsonPath("$.rows[?(@.name == 'Phạm Hải Tùng')].leaving").value(true))
			.andExpect(jsonPath("$.rows[?(@.name == 'Ngô Bảo Anh')].status").value("ASSIGNED"))
			.andExpect(jsonPath("$.confirmedCount").value(2))
			.andExpect(jsonPath("$.total").value(2));
		assertThat(jobVersion()).as("the Customer sees nothing of a waiting change").isEqualTo(jobVersion);
		String customer = this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.crew.length()").value(2))
			.andExpect(jsonPath("$.crewConfirmed").value(true))
			.andReturn().getResponse().getContentAsString();
		assertThat(customer).doesNotContain(TeamTestSupport.login(this.jdbc, this.anh));

		this.mvc.perform(get("/api/v1/crew/assignments").with(as(this.vy, Role.STUDENT)))
			.andExpect(jsonPath("$[0].changeWaiting").value(true))
			.andExpect(jsonPath("$[0].versionNo").value(2))
			.andExpect(jsonPath("$[0].sharePercent").value(30));
		this.mvc.perform(get("/api/v1/crew/assignments").with(as(this.tung, Role.STUDENT)))
			.andExpect(jsonPath("$[0].changeWaiting").value(false))
			.andExpect(jsonPath("$[0].versionNo").value(1));
		confirm(this.tung, 2).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_NOT_CONFIRMED"));
		confirm(this.anh, 1).andExpect(status().isConflict());
		decline(this.vy, 2).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("CREW_CHANGE_REQUIRED"));

		confirm(this.vy, 2).andExpect(status().isNoContent());
		assertThat(rowStatus(this.tung)).isEqualTo("ACTIVE");
		confirm(this.anh, 2).andExpect(status().isNoContent());

		assertThat(text("SELECT string_agg(version_no || status, ',' ORDER BY version_no) FROM job.crew_terms_version WHERE job_id = '"
				+ this.job + "'")).isEqualTo("1SUPERSEDED,2IN_FORCE");
		assertThat(rowStatus(this.tung)).isEqualTo("RELEASED");
		assertThat(text("SELECT released_reason FROM job.crew_row WHERE student_account_id = '" + this.tung + "'")).isEqualTo("REMOVED");
		assertThat(rowStatus(this.anh)).isEqualTo("ACTIVE");
		assertThat(this.crews.crewRelationship(this.job, this.tung).kind()).isEqualTo(Relationship.FORMER);
		assertThat(this.crews.crewRelationship(this.job, this.anh).kind()).isEqualTo(Relationship.ACTIVE);
		assertThat(jobVersion()).as("the usernames changed").isEqualTo(jobVersion + 1);
		assertThat(count("SELECT count(*) FROM team.crew_reservation r JOIN job.crew_row c ON c.id = r.crew_row_id"
				+ " WHERE c.student_account_id = '" + this.tung + "'")).isZero();
		assertThat(text("SELECT params ->> 'usernames' FROM notification.notification WHERE type = 'job.crew_changed' AND account_id = '"
				+ this.lan + "'")).contains(TeamTestSupport.login(this.jdbc, this.anh))
			.doesNotContain(TeamTestSupport.login(this.jdbc, this.tung));
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_member_removed' AND account_id = '"
				+ this.tung + "'")).isOne();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.crew[?(@ == '" + TeamTestSupport.login(this.jdbc, this.anh) + "')]").isNotEmpty());

		// FR-66: Tùng's data-deletion duty starts with his removal, on his read-only Board.
		await().atMost(Duration.ofSeconds(10)).until(() -> count("SELECT count(*) FROM notification.todo WHERE todo_key = "
				+ "'repo.confirm_data_deletion' AND account_id = '" + this.tung + "' AND closed_at IS NULL") == 1);
		this.mvc.perform(get("/api/v1/boards/" + this.job + "/data-deletion").with(as(this.tung, Role.STUDENT)))
			.andExpect(jsonPath("$.removed").value(true));
		this.mvc.perform(post("/api/v1/boards/" + this.job + "/data-deletion/NON_CODE_DATA").with(as(this.tung, Role.STUDENT))
			.with(csrf())).andExpect(status().isOk());
		awaitListeners();
	}

	@Test
	void aChangeWaitsWhileAMilestoneIsHeldAndTakesEffectInsideM5() throws Exception {
		this.jdbc.sql("UPDATE job.milestone SET state = 'FUNDED', funded_at = now(), crew_terms_version_no = 1 WHERE id = :id")
			.param("id", M2).update();
		change(crewVersion(), 40, Map.of(this.vy, 30, this.anh, 30)).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CREW_CHANGE_WHILE_HELD"));
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.change.held").value(true));

		this.jdbc.sql("UPDATE job.milestone SET state = 'PLANNED', funded_at = NULL, crew_terms_version_no = NULL WHERE id = :id")
			.param("id", M2).update();
		change(crewVersion(), 40, Map.of(this.vy, 30, this.anh, 30)).andExpect(status().isOk());
		// Funded meanwhile, Milestone 2 pins the version in force; funding never waits for the change.
		UUID handover = UUID.randomUUID();
		this.jdbc.sql("""
				UPDATE job.milestone SET state = 'SUBMITTED', funded_at = now(), crew_terms_version_no = 1, handover_id = :handover,
				    submitted_at = now()
				WHERE id = :id
				""").param("id", M2).param("handover", handover).update();
		confirm(this.vy, 2).andExpect(status().isNoContent());
		confirm(this.anh, 2).andExpect(status().isNoContent());
		assertThat(rowStatus(this.anh)).as("Milestone 2 is Held").isEqualTo("ASSIGNED");
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/crew").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.change.confirmed").value(2))
			.andExpect(jsonPath("$.change.waitingVersionNo").value(2));

		// A Code Job's M5 comes after delivery merged the Handover Commit (Story 8.5).
		Applied accepted = this.lifecycle.applyMilestone(Transition.M5, this.job, M2, Actor.user(this.lan), Trigger.USER,
				jobVersion(), Map.of("handoverId", handover, "merged", true));
		assertThat(rowStatus(this.anh)).isEqualTo("ACTIVE");
		assertThat(rowStatus(this.tung)).isEqualTo("RELEASED");
		assertThat(jobVersion()).as("inside an apply its own bump counts").isEqualTo(accepted.version());
		assertThat(this.crews.releaseTermsOf(M2).orElseThrow().versionNo()).as("Tùng stays a party of Milestone 2's pin")
			.isEqualTo(1);
		awaitListeners();
	}

	@Test
	void aLockedMemberIsRemovedInTheChangeAndJ8VoidsOneStillWaiting() throws Exception {
		this.jdbc.sql("UPDATE job.crew_row SET status = 'INACTIVE', window_end = now() WHERE student_account_id = :id")
			.param("id", this.tung).update();
		IdentityTestSupport.lock(this.jdbc, this.tung, "test: locked");
		change(crewVersion(), 40, Map.of(this.vy, 30, this.tung, 30)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("CREW_INACTIVE_PARTY"));
		change(crewVersion(), 40, Map.of(this.vy, 30, this.anh, 30)).andExpect(status().isOk());

		this.lifecycle.apply(Transition.J8, this.job, Actor.SYSTEM, Trigger.AUTO);
		assertThat(text("SELECT status FROM job.crew_terms_version WHERE job_id = '" + this.job + "' AND version_no = 2"))
			.isEqualTo("VOIDED");
		assertThat(rowStatus(this.anh)).isEqualTo("RELEASED");
		assertThat(text("SELECT released_reason FROM job.crew_row WHERE student_account_id = '" + this.anh + "'"))
			.isEqualTo("JOB_ENDED");
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'job.crew_change_voided' AND account_id IN ('"
				+ this.hung + "', '" + this.anh + "')")).isEqualTo(2);
		assertThat(rowStatus(this.vy)).isEqualTo("ACTIVE");
		awaitListeners();
	}

	private ResultActions change(long crewVersion, int mentor, Map<UUID, Integer> shares) throws Exception {
		StringBuilder parties = new StringBuilder("{\"accountId\":\"" + this.hung + "\",\"percent\":" + mentor + "}");
		shares.forEach((student, percent) -> parties.append(",{\"accountId\":\"").append(student).append("\",\"percent\":")
			.append(percent).append('}'));
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/crew/change").with(as(this.hung, Role.MENTOR)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewVersion\":" + crewVersion + ",\"shares\":[" + parties + "]}"));
	}

	private ResultActions confirm(UUID student, int version) throws Exception {
		return this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/confirm").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewTermsVersionNo\":%d,\"ndaVersion\":\"1.0\",\"publicCodeAcknowledged\":true,\"commitEmailNoticeAcknowledged\":true}"
				.formatted(version)));
	}

	private ResultActions decline(UUID student, int version) throws Exception {
		return this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/decline").with(as(student, Role.STUDENT)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"crewTermsVersionNo\":" + version + "}"));
	}

	private void share(UUID party, UUID row, int percent) {
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :party, :row, :percent)
				""").param("job", this.job).param("party", party).param("row", row).param("percent", percent).update();
	}

	private UUID member(String name) {
		UUID student = TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID()).param("team", this.team).param("student", student).update();
		return student;
	}

	private String rowStatus(UUID student) {
		return text("SELECT status FROM job.crew_row WHERE job_id = '" + this.job + "' AND student_account_id = '" + student
				+ "' ORDER BY created_at DESC LIMIT 1");
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long crewVersion() {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10))
			.until(() -> count("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL") == 0);
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', now(), now())
				""")
			.param("id", id).param("owner", owner).param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				boolean contributors = "/repos/lan/so-dat-ban/contributors".equals(exchange.getRequestURI().getPath());
				byte[] body = (contributors ? "[{\"id\":987654321,\"login\":\"lan\"}]" : "{}").getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(contributors ? 200 : 404, body.length);
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
