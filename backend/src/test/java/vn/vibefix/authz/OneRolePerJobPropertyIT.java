package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * AD-43's one-role property (Story 20.5): a seeded random run of the role-giving commands (a bid, J3's selection,
 * an invitation, adding a Crew member, a Crew change), with the NDA that moves a selected Job on, by random accounts
 * on random Jobs: four Open Job Posts and two Active Jobs with a Crew, with large Crews on so that a running Job's
 * Crew may change. After every step no account holds two roles in one Job (Customer, Mentor, counted Crew row), no
 * Job's own Customer bids on it or is invited to it, and every refusal is one of its command's documented answers
 * ({@link #REFUSALS}), {@code ROLE_TAKEN} or another guard, never a server error: the commands check the role under
 * the Job lock, and the schema constraints are only the backstop. Each of the five commands succeeds at least once,
 * and every success comes from the holder the command requires ({@link #holder}).
 * Each person can mentor and join a Crew; GitHub answers that nobody contributed.
 */
class OneRolePerJobPropertyIT extends IntegrationTest {

	private static final long SEED = 20_5L;

	private static final int STEPS = 200;

	private static final int PEOPLE = 8;

	private static final int POSTS = 4;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The 4.3 guards a new Crew member passes, in Crew add and in a Crew change alike (FR-75). */
	private static final Set<String> NEW_MEMBER = Set.of("GUARD_FAILED STUDENT_NOT_VERIFIED", "GUARD_FAILED STUDENT_ON_CREW",
			"GUARD_FAILED CONFLICT_OF_INTEREST", "GUARD_FAILED APPLICANT_IS_CONTRIBUTOR",
			"GUARD_FAILED CONTRIBUTORS_UNKNOWN", "GUARD_FAILED NOT_IN_TEAM");

	/**
	 * Each command's documented refusals, as {@code CODE} or {@code CODE REASON}, from its controller and its
	 * {@code @throws}: {@code Applications.submit}, {@code Invitations.invite}, {@code J3Select.check} and the
	 * transition's {@code STALE}, {@code NdaSignatures.sign}, {@code CrewService.add}, {@code CrewChanges.propose}.
	 */
	static final Map<Command, Set<String>> REFUSALS = Map.of(
			Command.BID,
			Set.of("NOT_FOUND", "FORBIDDEN GITHUB_NOT_LINKED", "GUARD_FAILED ROLE_TAKEN", "GUARD_FAILED PROFILE_REQUIRED",
					"GUARD_FAILED JOB_POST_HIDDEN", "GUARD_FAILED JOB_POST_NOT_OPEN", "CONFLICT ALREADY_APPLIED",
					"RATE_LIMITED", "GUARD_FAILED MENTOR_JOB_LIMIT",
					"GUARD_FAILED CONFLICT_OF_INTEREST", "GUARD_FAILED APPLICANT_IS_CONTRIBUTOR",
					"GUARD_FAILED CONTRIBUTORS_UNKNOWN"),
			Command.INVITE,
			Set.of("NOT_FOUND", "GUARD_FAILED ROLE_TAKEN", "GUARD_FAILED JOB_POST_HIDDEN", "GUARD_FAILED JOB_POST_NOT_OPEN",
					"GUARD_FAILED INVITEE_NOT_AVAILABLE", "CONFLICT ALREADY_INVITED", "CONFLICT INVITEE_ALREADY_APPLIED",
					"RATE_LIMITED INVITE_LIMIT_JOB_POST", "RATE_LIMITED INVITE_LIMIT_DAILY"),
			Command.SELECT,
			Set.of("NOT_FOUND", "STALE", "STALE STATE_CHANGED", "GUARD_FAILED ROLE_TAKEN", "GUARD_FAILED MENTOR_NOT_AVAILABLE",
					"GUARD_FAILED MENTOR_AT_JOB_LIMIT"),
			Command.NDA,
			Set.of("NOT_FOUND", "STALE", "STALE STATE_CHANGED", "FORBIDDEN GITHUB_NOT_LINKED",
					"VALIDATION NDA_VERSION_OUTDATED", "VALIDATION COMMIT_EMAIL_NOT_ACKNOWLEDGED"),
			Command.CREW_ADD, union(NEW_MEMBER, "NOT_FOUND", "STALE", "GUARD_FAILED ROLE_TAKEN",
					"GUARD_FAILED CREW_CHANGE_REQUIRED", "GUARD_FAILED CREW_LOCKED", "GUARD_FAILED CREW_LIMIT"),
			Command.CREW_CHANGE, union(NEW_MEMBER, "NOT_FOUND", "STALE", "STALE STATE_CHANGED", "GUARD_FAILED ROLE_TAKEN",
					"GUARD_FAILED CREW_CHANGE_WHILE_HELD", "GUARD_FAILED CREW_LIMIT", "VALIDATION SHARES_INVALID"));

	/** The five role-giving commands, each of which the run must see succeed. */
	static final Set<Command> ROLE_GIVING = Set.of(Command.BID, Command.SELECT, Command.INVITE, Command.CREW_ADD,
			Command.CREW_CHANGE);

	private static final HttpServer GITHUB = start();

	enum Command {

		BID, INVITE, SELECT, NDA, CREW_ADD, CREW_CHANGE

	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	private final List<UUID> people = new ArrayList<>();

	private final List<UUID> jobs = new ArrayList<>();

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
		// A running Job's Crew changes only with large Crews on (AD-30, Story 11.3).
		registry.add("vibefix.features.large-crews", () -> "true");
	}

	@AfterAll
	static void stopGitHub() {
		GITHUB.stop(0);
	}

	@Test
	void noRandomRunOfRoleGivingCommandsGivesAnAccountTwoRolesInAJob() throws Exception {
		world();
		Random random = new Random(SEED);
		Map<String, Integer> answers = new TreeMap<>();
		List<String> violations = new ArrayList<>();
		for (int step = 1; step <= STEPS; step++) {
			Command command = Command.values()[random.nextInt(Command.values().length)];
			UUID job = job(command, random);
			String what = "step " + step + " " + command + " on " + this.jobs.indexOf(job);
			Step request = request(command, job, random);
			MockHttpServletResponse response = this.mvc.perform(request.request()).andReturn().getResponse();
			String answer = answer(command, what, response, violations);
			if (response.getStatus() < 300) {
				holder(command, request).ifPresent(wrong -> violations.add(what + ": succeeded for " + wrong));
			}
			answers.merge(command + " " + answer, 1, Integer::sum);
			overlaps().forEach(overlap -> violations.add(what + ": two roles in one Job " + overlap));
			ownCustomer().forEach(bid -> violations.add(what + ": the Job's own Customer bid or was invited " + bid));
		}
		assertThat(violations).as("answers %s", answers).isEmpty();
		for (Command command : ROLE_GIVING) {
			assertThat(answers.keySet()).as("%s succeeds at least once: %s", command, answers)
				.anyMatch(answer -> answer.startsWith(command + " 2"));
		}
		assertThat(answers.keySet()).as("a second role is refused: %s", answers)
			.anyMatch(answer -> answer.endsWith("ROLE_TAKEN"));
	}

	/**
	 * One step's request, its caller, and the Job's holders before it: the Customer, the Mentor, and the Mentor of the
	 * selected bid.
	 */
	record Step(MockHttpServletRequestBuilder request, UUID caller, UUID customer, @Nullable UUID mentor,
			@Nullable UUID selectedMentor) {
	}

	/**
	 * The holder a success must come from (review iteration 4, B10): the Job's Mentor for a Crew add and a Crew change,
	 * the Mentor of the selected bid for the NDA, its Customer for a selection and an invitation, and anyone but its
	 * Customer for a bid. Returns what was wrong, if anything.
	 */
	private static Optional<String> holder(Command command, Step step) {
		boolean held = switch (command) {
			case BID -> !step.caller().equals(step.customer());
			case INVITE, SELECT -> step.caller().equals(step.customer());
			case NDA -> step.caller().equals(step.selectedMentor());
			case CREW_ADD, CREW_CHANGE -> step.caller().equals(step.mentor());
		};
		return held ? Optional.empty()
				: Optional.of("a caller who is not its holder (" + step.caller() + "; Customer " + step.customer()
						+ ", Mentor " + step.mentor() + ", selected " + step.selectedMentor() + ")");
	}

	/** One step's request: the command's caller, Job and target, chosen so that a role's holder often acts. */
	private Step request(Command command, UUID job, Random random) {
		UUID customer = this.jdbc.sql("SELECT customer_account_id FROM job.job WHERE id = :id").param("id", job)
			.query(UUID.class).single();
		UUID mentor = this.jdbc.sql("SELECT mentor_account_id FROM job.job WHERE id = :id").param("id", job)
			.query(UUID.class).optional().orElse(null);
		UUID anyone = person(random);
		UUID target = random.nextInt(4) == 0 ? (random.nextBoolean() || mentor == null ? customer : mentor) : person(random);
		List<UUID> crew = this.jdbc.sql("""
				SELECT member_account_id FROM job.crew_row WHERE job_id = :job AND status <> 'RELEASED'
				""").param("job", job).query(UUID.class).list().stream().sorted(this::byPerson).toList();
		UUID member = !crew.isEmpty() && random.nextBoolean() ? crew.get(random.nextInt(crew.size())) : target;
		// The Mentor's own Team member, whom a Crew add can take (FR-75): the next person.
		UUID teamMember = mentor == null ? target : this.people.get((this.people.indexOf(mentor) + 1) % PEOPLE);
		UUID student = random.nextInt(3) == 0 ? teamMember : target;
		UUID selectedMentor = this.jdbc.sql("""
				SELECT a.mentor_account_id FROM job.job j JOIN matching.application a ON a.id = j.selected_application_id
				WHERE j.id = :id
				""").param("id", job).query(UUID.class).optional().orElse(null);
		// The bid to select is drawn before the caller, as the seed's run always drew them.
		UUID application = command == Command.SELECT ? application(job, random) : null;
		UUID caller = switch (command) {
			case BID -> random.nextInt(5) == 0 ? customer : anyone;
			case INVITE, SELECT -> random.nextInt(10) < 7 ? customer : anyone;
			case NDA, CREW_ADD, CREW_CHANGE -> random.nextInt(10) < 7 && mentor != null ? mentor : anyone;
		};
		MockHttpServletRequestBuilder request = switch (command) {
			case BID -> post("/api/v1/mentor/job-posts/" + job + "/applications").content("""
					{"priceVnd":800000,"timelineDays":7,"approach":"Tái hiện lỗi rồi sửa.","publicCodeAcknowledged":true}""");
			case INVITE -> post("/api/v1/jobs/" + job + "/invitations").content("{\"mentorId\":\"" + target + "\"}");
			case SELECT -> post("/api/v1/jobs/" + job + "/applications/" + application + "/select")
				.content("{\"version\":" + version(job, "version") + "}");
			case NDA -> post("/api/v1/mentor/jobs/" + job + "/nda")
				.content("{\"ndaVersion\":\"1.0\",\"commitEmailNoticeAcknowledged\":true}");
			case CREW_ADD -> post("/api/v1/mentor/jobs/" + job + "/crew")
				.content("{\"studentId\":\"" + student + "\",\"crewVersion\":" + version(job, "crew_version") + "}");
			case CREW_CHANGE -> put("/api/v1/mentor/jobs/" + job + "/crew/change").content("""
					{"crewVersion":%d,"shares":[{"accountId":"%s","percent":30},{"accountId":"%s","percent":70}]}"""
				.formatted(version(job, "crew_version"), mentor == null ? anyone : mentor, member));
		};
		return new Step(request.with(as(caller)).with(csrf()).contentType(MediaType.APPLICATION_JSON), caller, customer,
				mentor, selectedMentor);
	}

	/**
	 * The step's answer as {@code status [code [reason]]}; a server error, or a refusal that is not one of the
	 * command's {@link #REFUSALS}, is a violation.
	 */
	private static String answer(Command command, String what, MockHttpServletResponse response, List<String> violations)
			throws Exception {
		int status = response.getStatus();
		if (status < 300) {
			return String.valueOf(status);
		}
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		if (status >= 500 || !body.startsWith("{")) {
			violations.add(what + ": " + status + " " + body);
			return String.valueOf(status);
		}
		JsonNode problem = JSON.readTree(body);
		String code = problem.path("code").asString(null);
		String reason = problem.path("reason").asString(null);
		String refusal = code + (reason == null ? "" : " " + reason);
		if (code == null || !REFUSALS.get(command).contains(refusal)) {
			violations.add(what + ": " + refusal + " is no documented refusal of " + command + " (" + status + " " + body + ")");
		}
		return status + " " + code + (reason == null ? "" : " " + reason);
	}

	/** Every (Job, account) pair with more than one relation: Customer, Mentor, or a counted Crew row (AD-43). */
	private List<String> overlaps() {
		return this.jdbc.sql("""
				SELECT job_id || ' ' || account_id || ' ' || string_agg(kind, '+') FROM (
				    SELECT id AS job_id, customer_account_id AS account_id, 'CUSTOMER' AS kind FROM job.job
				    UNION ALL
				    SELECT id, mentor_account_id, 'MENTOR' FROM job.job WHERE mentor_account_id IS NOT NULL
				    UNION ALL
				    SELECT DISTINCT job_id, member_account_id, 'CREW' FROM job.crew_row
				    WHERE status <> 'RELEASED' OR activated_at IS NOT NULL
				) relation
				GROUP BY job_id, account_id HAVING count(*) > 1
				""").query(String.class).list();
	}

	/** Bids and invitations of a Job's own Customer, which a subject relation never is (AD-43). */
	private List<String> ownCustomer() {
		return this.jdbc.sql("""
				SELECT 'bid ' || a.id FROM matching.application a JOIN job.job j ON j.id = a.job_id
				WHERE a.mentor_account_id = j.customer_account_id
				UNION ALL
				SELECT 'invitation ' || i.id FROM matching.invitation i JOIN job.job j ON j.id = i.job_id
				WHERE i.mentor_account_id = j.customer_account_id
				""").query(String.class).list();
	}

	/**
	 * Eight people who can each mentor a Job and join a Crew: GitHub linked, a profile with a display name, a live
	 * student verification, a Team of their own with the next person as its member; four of them post an Open
	 * Job Post on a Product of their own, whose GitHub token is kept.
	 */
	private void world() {
		for (int i = 0; i < PEOPLE; i++) {
			UUID person = TeamTestSupport.mentor(this.jdbc, "Người " + i, List.of(TeamTestSupport.REACT), "React");
			IdentityTestSupport.verifyStudent(this.jdbc, person, LocalDate.of(2026, 9, 30));
			this.people.add(person);
		}
		for (int i = 0; i < PEOPLE; i++) {
			UUID team = UUID.randomUUID();
			this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
				.param("id", team)
				.param("mentor", this.people.get(i))
				.update();
			this.jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
				.param("id", UUID.randomUUID())
				.param("team", team)
				.param("student", this.people.get((i + 1) % PEOPLE))
				.update();
		}
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		for (int i = 0; i < POSTS; i++) {
			UUID customer = this.people.get(i);
			CustomerTokens.keep(this.context, customer, "gho_property_token_" + i);
			UUID product = UUID.randomUUID();
			this.jdbc.sql("""
					INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
					    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
					    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
					VALUES (:id, :owner, :name, 'Mô tả', ARRAY['LOVABLE'], :github, :repository, 'User', 'main', '1.0', '1.0',
					    '1.0', :now, :now)
					""")
				.param("id", product)
				.param("owner", customer)
				.param("name", "Sản phẩm " + i)
				.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
				.param("repository", "nguoi" + i + "/san-pham")
				.param("now", now)
				.update();
			UUID job = UUID.randomUUID();
			this.jdbc.sql("""
					INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
					    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
					VALUES (:id, :product, :owner, 'OPEN', :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
					    DATE '2026-12-01', '{"REVISION_ROUNDS":2}'::jsonb, 14, :now, :lapses, 1, :now, :now)
					""")
				.param("id", job)
				.param("product", product)
				.param("owner", customer)
				.param("title", "Việc " + i)
				.param("now", now)
				.param("lapses", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(14))))
				.update();
			this.jobs.add(job);
		}
		running(4, 6);
		running(5, 7);
	}

	/**
	 * An Active Job of person {@code customer}'s own Product, mentored by person {@code mentor} with the next person,
	 * their Team's member, as its one active Crew member under Crew Terms version 1 (Mentor 30 %): a running Job,
	 * whose Crew a change may move.
	 */
	private void running(int customer, int mentor) {
		UUID customerId = this.people.get(customer);
		UUID mentorId = this.people.get(mentor);
		UUID memberId = this.people.get((mentor + 1) % PEOPLE);
		CustomerTokens.keep(this.context, customerId, "gho_property_token_" + customer);
		UUID product = JobContent.product(this.jdbc, customerId, "Sản phẩm " + customer);
		UUID job = JobContent.activeJob(this.jdbc, product, customerId, mentorId, "Việc đang làm " + customer);
		UUID row = JobContent.crewRow(this.jdbc, job, memberId, "ACTIVE", null);
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 40, :mentor, :at)
				""").param("job", job).param("mentor", mentorId).param("at", Timestamp.from(JobContent.SIGNED)).update();
		for (Object[] share : new Object[][] { { mentorId, null, 30 }, { memberId, row, 70 } }) {
			this.jdbc.sql("""
					INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
					VALUES (:job, 1, :party, :row, :percent)
					""").param("job", job).param("party", share[0]).param("row", share[1]).param("percent", share[2]).update();
		}
		this.jobs.add(job);
	}

	private int byPerson(UUID one, UUID other) {
		return Integer.compare(this.people.indexOf(one), this.people.indexOf(other));
	}

	private static Set<String> union(Set<String> base, String... more) {
		Set<String> all = new HashSet<>(base);
		all.addAll(List.of(more));
		return Set.copyOf(all);
	}

	/**
	 * The step's Job: mostly one in the state the command needs, so that the run moves Jobs on (an Open Job Post
	 * for a bid, an invitation and J3, a selected one for the NDA, one in Agreement for a Crew add, a running one for
	 * a Crew change), and otherwise any, whose refusal the run checks too.
	 */
	private UUID job(Command command, Random random) {
		List<String> states = switch (command) {
			case BID, INVITE, SELECT -> List.of("OPEN");
			case NDA -> List.of("SELECTING");
			case CREW_ADD -> List.of("AGREEMENT");
			case CREW_CHANGE -> List.of("STARTING", "ACTIVE", "CLOSING");
		};
		List<UUID> fitting = this.jobs.stream()
			.filter(job -> states.contains(this.jdbc.sql("SELECT state FROM job.job WHERE id = :id")
				.param("id", job)
				.query(String.class)
				.single()))
			.toList();
		boolean fit = !fitting.isEmpty() && random.nextInt(4) != 0;
		return fit ? fitting.get(random.nextInt(fitting.size())) : this.jobs.get(random.nextInt(this.jobs.size()));
	}

	private UUID person(Random random) {
		return this.people.get(random.nextInt(this.people.size()));
	}

	/** A bid on the Job, any status, in the order of its bidders, or an ID that names none. */
	private UUID application(UUID job, Random random) {
		Map<UUID, UUID> byBidder = new TreeMap<>((one, other) -> Integer.compare(this.people.indexOf(one),
				this.people.indexOf(other)));
		this.jdbc.sql("SELECT id, mentor_account_id FROM matching.application WHERE job_id = :job")
			.param("job", job)
			.query(rs -> {
				byBidder.put(rs.getObject("mentor_account_id", UUID.class), rs.getObject("id", UUID.class));
			});
		List<UUID> bids = List.copyOf(byBidder.values());
		return bids.isEmpty() || random.nextInt(10) == 0 ? UUID.randomUUID() : bids.get(random.nextInt(bids.size()));
	}

	private long version(UUID job, String column) {
		return this.jdbc.sql("SELECT " + column + " FROM job.job WHERE id = :id").param("id", job).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, Role.MENTOR, this.clock.instant());
	}

	/** GitHub, answering that the Source Repository has no contributors, whoever asks. */
	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				byte[] body = (exchange.getRequestURI().getPath().endsWith("/contributors") ? "[]" : "{}")
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
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
