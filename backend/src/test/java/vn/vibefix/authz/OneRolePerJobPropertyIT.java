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
import vn.vibefix.support.TestPeople;

/**
 * AD-43's one-role property (Story 20.5, Crew from Crew Applications since Story 21.5): a seeded random run of the
 * role-giving commands (a bid, J3's selection, an invitation, the Mentor's pick of a Crew Application, the picked
 * person's signature), with the NDA that moves a selected Job on, the Mentor's recruitment and the Crew Applications
 * that feed the picks, by random accounts on random Jobs: four Open Job Posts and two recruiting Active Jobs with a
 * Crew, with large Crews on so that a Crew holds up to five. After every step no account holds two roles in one Job
 * (Customer, Mentor, counted Crew row), no Job's own Customer bids on it or is invited to it, and every refusal is one
 * of its command's documented answers ({@link #REFUSALS}), {@code ROLE_TAKEN} or another guard, never a server error:
 * the commands check the role under the Job lock, and the schema constraints are only the backstop. Each role-giving
 * command succeeds at least once, and every success comes from the holder the command requires ({@link #holder}).
 * Each person can mentor and join a Crew; GitHub answers that nobody contributed.
 */
class OneRolePerJobPropertyIT extends IntegrationTest {

	private static final long SEED = 20_5L;

	private static final int STEPS = 200;

	private static final int PEOPLE = 8;

	private static final int POSTS = 4;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The guards a new Crew member passes at the pick and again at the signature (FR-81, AD-41, A-16). */
	private static final Set<String> NEW_MEMBER = Set.of("GUARD_FAILED ROLE_TAKEN", "GUARD_FAILED APPLICANT_UNAVAILABLE",
			"GUARD_FAILED CREW_REJOIN_REFUSED", "GUARD_FAILED CONFLICT_OF_INTEREST", "GUARD_FAILED APPLICANT_IS_CONTRIBUTOR",
			"GUARD_FAILED CONTRIBUTORS_UNKNOWN");

	/**
	 * Each command's documented refusals, as {@code CODE} or {@code CODE REASON}, from its controller and its
	 * {@code @throws}: {@code Applications.submit}, {@code Invitations.invite}, {@code J3Select.check} and the
	 * transition's {@code STALE}, {@code NdaSignatures.sign}, {@code JobRecruitment.set}, {@code CrewApplications.send},
	 * {@code CrewCommitments.pick} with {@code job.addCrew}, and {@code CrewSignatures.sign} with {@code job.activateCrew}.
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
			Command.RECRUIT,
			Set.of("NOT_FOUND", "STALE", "GUARD_FAILED RECRUITMENT_UNAVAILABLE", "VALIDATION SLOTS_INVALID"),
			Command.APPLY,
			Set.of("NOT_FOUND", "GUARD_FAILED ROLE_TAKEN", "GUARD_FAILED RECRUITMENT_CLOSED", "GUARD_FAILED CREW_FULL",
					"GUARD_FAILED CREW_REJOIN_REFUSED", "GUARD_FAILED ON_ANOTHER_CREW", "GUARD_FAILED CONFLICT_OF_INTEREST",
					"GUARD_FAILED APPLICANT_IS_CONTRIBUTOR", "GUARD_FAILED CONTRIBUTORS_UNKNOWN", "CONFLICT ALREADY_APPLIED",
					"RATE_LIMITED"),
			Command.PICK, union(NEW_MEMBER, "NOT_FOUND", "STALE", "CONFLICT NOT_PICKABLE", "GUARD_FAILED RECRUITMENT_CLOSED",
					"GUARD_FAILED CREW_FULL"),
			Command.SIGN, union(NEW_MEMBER, "NOT_FOUND", "STALE", "GUARD_FAILED PICK_ENDED"));

	/** The five role-giving commands, each of which the run must see succeed. */
	static final Set<Command> ROLE_GIVING = Set.of(Command.BID, Command.SELECT, Command.INVITE, Command.PICK,
			Command.SIGN);

	private static final HttpServer GITHUB = start();

	enum Command {

		BID, INVITE, SELECT, NDA, RECRUIT, APPLY, PICK, SIGN

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
		// A Crew holds up to five rows only with large Crews on (AD-30).
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
	 * One step's request, its caller, and the Job's holders before it: the Customer, the Mentor, the Mentor of the
	 * selected bid, and the person of the pick a signature names.
	 */
	record Step(MockHttpServletRequestBuilder request, UUID caller, UUID customer, @Nullable UUID mentor,
			@Nullable UUID selectedMentor, @Nullable UUID picked) {
	}

	/**
	 * The holder a success must come from (review iteration 4, B10): the Job's Mentor for the recruitment and a pick,
	 * the picked person for a signature, the Mentor of the selected bid for the NDA, its Customer for a selection and an
	 * invitation, and anyone but its Customer for a bid, and anyone but its Customer and Mentor for a Crew Application.
	 * Returns what was wrong, if anything.
	 */
	private static Optional<String> holder(Command command, Step step) {
		boolean held = switch (command) {
			case BID -> !step.caller().equals(step.customer());
			case INVITE, SELECT -> step.caller().equals(step.customer());
			case NDA -> step.caller().equals(step.selectedMentor());
			case RECRUIT, PICK -> step.caller().equals(step.mentor());
			case APPLY -> !step.caller().equals(step.customer()) && !step.caller().equals(step.mentor());
			case SIGN -> step.caller().equals(step.picked());
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
		UUID selectedMentor = this.jdbc.sql("""
				SELECT a.mentor_account_id FROM job.job j JOIN matching.application a ON a.id = j.selected_application_id
				WHERE j.id = :id
				""").param("id", job).query(UUID.class).optional().orElse(null);
		// The pick to make or to sign is drawn before the caller: a pending or a picked Crew Application of the Job.
		UUID crewApplication = command == Command.PICK ? crewApplication(job, "PENDING", random) : null;
		UUID picked = command == Command.SIGN ? pickedPerson(job, random) : null;
		// The bid to select is drawn before the caller, as the seed's run always drew them.
		UUID application = command == Command.SELECT ? application(job, random) : null;
		UUID caller = switch (command) {
			case BID -> random.nextInt(5) == 0 ? customer : anyone;
			case INVITE, SELECT -> random.nextInt(10) < 7 ? customer : anyone;
			case NDA, RECRUIT, PICK -> random.nextInt(10) < 7 && mentor != null ? mentor : anyone;
			case APPLY -> anyone;
			case SIGN -> random.nextInt(10) < 7 && picked != null ? picked : anyone;
		};
		MockHttpServletRequestBuilder request = switch (command) {
			case BID -> post("/api/v1/mentor/job-posts/" + job + "/applications").content("""
					{"priceVnd":800000,"timelineDays":7,"approach":"Tái hiện lỗi rồi sửa.","publicCodeAcknowledged":true}""");
			case INVITE -> post("/api/v1/jobs/" + job + "/invitations").content("{\"mentorId\":\"" + target + "\"}");
			case SELECT -> post("/api/v1/jobs/" + job + "/applications/" + application + "/select")
				.content("{\"version\":" + version(job, "version") + "}");
			case NDA -> post("/api/v1/mentor/jobs/" + job + "/nda")
				.content("{\"ndaVersion\":\"2.0\",\"commitEmailNoticeAcknowledged\":true}");
			case RECRUIT -> put("/api/v1/mentor/jobs/" + job + "/recruitment").content("""
					{"on":true,"openSlots":%d,"skillIds":[],"crewVersion":%d}"""
				.formatted(1 + random.nextInt(2), version(job, "crew_version")));
			case APPLY -> post("/api/v1/mentor/job-posts/" + job + "/crew-applications").content("""
					{"note":"Mình muốn tham gia nhóm.","skillIds":[],"publicCodeAcknowledged":true}""");
			case PICK -> post("/api/v1/mentor/jobs/" + job + "/crew-applications/" + crewApplication + "/pick");
			case SIGN -> post("/api/v1/crew/jobs/" + job + "/commitment").content("""
					{"ndaVersion":"2.0","commitmentVersion":"1.0","publicCodeAcknowledged":true,
					 "commitEmailNoticeAcknowledged":true}""");
		};
		return new Step(request.with(as(caller)).with(csrf()).contentType(MediaType.APPLICATION_JSON), caller, customer,
				mentor, selectedMentor, picked);
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
	 * Eight people who can each mentor a Job and join a Crew: GitHub linked, a profile with a display name, and a live
	 * student verification; four of them post an Open Job Post on a Product of their own, whose GitHub token is kept.
	 */
	private void world() {
		for (int i = 0; i < PEOPLE; i++) {
			UUID person = TestPeople.mentor(this.jdbc, "Người " + i, List.of(TestPeople.REACT), "React");
			IdentityTestSupport.verifyStudent(this.jdbc, person, LocalDate.of(2026, 9, 30));
			this.people.add(person);
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
	 * An Active Job of person {@code customer}'s own Product, mentored by person {@code mentor} with the next person as
	 * its one active Crew member, whose Mentor recruits two more (FR-81): a running Job whose Crew grows by picks.
	 */
	private void running(int customer, int mentor) {
		UUID customerId = this.people.get(customer);
		UUID mentorId = this.people.get(mentor);
		UUID memberId = this.people.get((mentor + 1) % PEOPLE);
		CustomerTokens.keep(this.context, customerId, "gho_property_token_" + customer);
		UUID product = JobContent.product(this.jdbc, customerId, "Sản phẩm " + customer);
		UUID job = JobContent.activeJob(this.jdbc, product, customerId, mentorId, "Việc đang làm " + customer);
		JobContent.selectedBid(this.jdbc, job, product, customerId, mentorId, JobContent.SIGNED);
		JobContent.crewRow(this.jdbc, job, memberId, "ACTIVE", null);
		this.jdbc.sql("UPDATE job.job SET recruiting = true, recruiting_since = :at, open_slots = 2 WHERE id = :id")
			.param("id", job).param("at", Timestamp.from(JobContent.SIGNED)).update();
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
	 * for a bid, an invitation and J3, a selected one for the NDA, one from J4 until J8 for the recruitment, a Crew
	 * Application, a pick and a signature), and otherwise any, whose refusal the run checks too.
	 */
	private UUID job(Command command, Random random) {
		List<String> states = switch (command) {
			case BID, INVITE, SELECT -> List.of("OPEN");
			case NDA -> List.of("SELECTING");
			case RECRUIT, APPLY, PICK, SIGN -> List.of("AGREEMENT", "STARTING", "ACTIVE");
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

	/** A Crew Application of the Job in {@code status}, in the order of its applicants, or an ID that names none. */
	private UUID crewApplication(UUID job, String status, Random random) {
		Map<UUID, UUID> byApplicant = new TreeMap<>(this::byPerson);
		this.jdbc.sql("SELECT id, applicant_account_id FROM matching.crew_application WHERE job_id = :job AND status = :status")
			.param("job", job)
			.param("status", status)
			.query(rs -> {
				byApplicant.put(rs.getObject("applicant_account_id", UUID.class), rs.getObject("id", UUID.class));
			});
		List<UUID> found = List.copyOf(byApplicant.values());
		return found.isEmpty() || random.nextInt(10) == 0 ? UUID.randomUUID() : found.get(random.nextInt(found.size()));
	}

	/** The person of a pick of the Job waiting for the signature, in the order of the people, if any. */
	private @Nullable UUID pickedPerson(UUID job, Random random) {
		List<UUID> picked = this.jdbc.sql("""
				SELECT applicant_account_id FROM matching.crew_application WHERE job_id = :job AND status = 'PICKED'
				""").param("job", job).query(UUID.class).list().stream().sorted(this::byPerson).toList();
		return picked.isEmpty() ? null : picked.get(random.nextInt(picked.size()));
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
