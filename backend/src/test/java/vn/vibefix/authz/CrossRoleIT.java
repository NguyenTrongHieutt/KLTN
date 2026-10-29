package vn.vibefix.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.Crews;
import vn.vibefix.job.JobRole;
import vn.vibefix.job.JobRoles;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * AD-20, SG-64, review 2026-10-27 A-20 (Story 20.5): one person's roles never leak into each other. Three people
 * hold two roles each: X is the Customer of Job A and an {@code ACTIVE} Crew member of Job B; Y is the Mentor of Job
 * C and a Crew member of Job D; Z is a former Crew member of Job E, whose window ended before E's later content was
 * written, and the Customer of Job F. Every Job carries canaries of its Crew (a member's shown name, email and
 * LinkedIn link, the Crew Chat, a Work Item note, an Attachment, a Page, the Crew's parts of a released Milestone)
 * and of its Customer (the Job Chat, the Customer's contact email), and one object of its own for every placeholder
 * the sweep fills.
 * <p>
 * The suite is table-driven: for every {@code GET} endpoint of {@code authz/matrix.csv} with a Job-scoped
 * placeholder ({@link MatrixRow#JOB_SCOPED}), it fills every placeholder from each of the person's Jobs, calls as
 * the person, and fails on any canary of the other context: through a Job, nothing of the person's other Job, and
 * nothing of this Job its role there hides (the Crew's for its Customer, the Customer's and another member's
 * part for a Crew member, and, for a former member, what was written after their {@code window_end}, IDs
 * included); the other Job's object IDs are forbidden too, a server error fails the sweep, and every swept read
 * must let some person past its policy unless {@link #NOT_SWEPT} says why not. Each role must also read its own Job
 * ({@link #READS}) and see the busy marker as that role, the Customer-only reads and commands refuse everyone else
 * with {@code NOT_FOUND}, the Mentor's reads ({@link #MENTOR_ONLY}) refuse every Job where the person is not its
 * Mentor, and the global reads answer and run the same scan, CUS-7's list of every Job a person holds a role in
 * ({@code /my-jobs}, Story 20.6) among them.
 */
class CrossRoleIT extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

	private static final Instant WINDOW_END = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(5));

	private static final Instant WRITTEN = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1));

	/** The global reads where every Job of a person meets: lists, inboxes, balances. Each answers 200. */
	private static final List<String> GLOBAL_READS = List.of("/api/v1/jobs", "/api/v1/my-jobs", "/api/v1/mentor/jobs",
			"/api/v1/crew/assignments", "/api/v1/team-issues", "/api/v1/notifications", "/api/v1/todos",
			"/api/v1/balance", "/api/v1/payments/history", "/api/v1/products", "/api/v1/profile",
			"/api/v1/mentor/applications", "/api/v1/account/data");

	/** The reads each role makes of its own Job, which must answer 200 through that Job. */
	private static final Map<Held, List<String>> READS = Map.of(Held.CUSTOMER,
			List.of("/api/v1/jobs/{jobId}", "/api/v1/jobs/{jobId}/funding", "/api/v1/jobs/{jobId}/agreement",
					"/api/v1/jobs/{jobId}/chat", "/api/v1/jobs/{jobId}/chat/messages"),
			Held.MENTOR,
			List.of("/api/v1/mentor/jobs/{jobId}", "/api/v1/mentor/jobs/{jobId}/crew",
					"/api/v1/crew-chats/{crewChatId}/messages", "/api/v1/boards/{boardId}/work-items",
					"/api/v1/boards/{boardId}/handovers"),
			Held.ACTIVE_CREW,
			List.of("/api/v1/crew-chats/{crewChatId}/messages", "/api/v1/boards/{boardId}/work-items",
					"/api/v1/work-items/{workItemId}"),
			Held.FORMER_CREW, List.of("/api/v1/crew-chats/{crewChatId}/messages"));

	/**
	 * The Mentor's own reads of a Job, every {@code GET} under {@code /api/v1/mentor/jobs/{jobId}}: each answers
	 * {@code NOT_FOUND} through every Job where the person is not the Mentor.
	 */
	private static final List<String> MENTOR_ONLY = MatrixRow.loadMatrix()
		.stream()
		.filter(row -> row.method() == HttpMethod.GET
				&& (row.path().equals("/api/v1/mentor/jobs/{jobId}") || row.path().startsWith("/api/v1/mentor/jobs/{jobId}/")))
		.map(MatrixRow::path)
		.distinct()
		.toList();

	/**
	 * The Customer's own reads, which answer {@code NOT_FOUND} through every Job where the person is not the Customer:
	 * every {@code {jobId}} {@code GET} that on the crew Job only the Customer gets past (review iteration 4, B4).
	 */
	private static final List<String> CUSTOMER_ONLY = customerOnly();

	/**
	 * The Crew's own reads of a Job, which answer {@code NOT_FOUND} through every Job where the person is neither its
	 * Mentor nor on its Crew (review iteration 4, V1): every Job or Board {@code GET} whose crew-Job rows let
	 * {@code CREW_ACTIVE} past (a {@code 2xx}) and refuse the Customer with {@code NOT_FOUND}, the Crew Chat tab of
	 * b226eba's cross-role refusal among them.
	 */
	private static final List<String> CREW_ONLY = crewOnly();

	/**
	 * The swept reads no person gets past, each with why (review iterations 3 and 4, B5, E12): the Administrators'
	 * reads, since none of the three people is one, and the Final Merge, which no Job of the sweep has opened. They
	 * are swept for canaries like every other read; only the requirement that some person gets past them spares them.
	 */
	private static final Map<String, String> NOT_SWEPT = notSwept();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private StoredFiles files;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private JobRoles roles;

	@Autowired
	private Crews crews;

	/** Stores a file as its owning module stores it (AD-21); returns its ID. */
	interface Uploads {

		UUID store(String purpose, UUID author, String fileName);

	}

	/** The role a person holds in one of their Jobs. */
	enum Held {

		CUSTOMER, MENTOR, ACTIVE_CREW, FORMER_CREW

	}

	/** One person and their two Jobs, by the role held in each. */
	record Person(String name, UUID account, Map<JobWorld, Held> jobs) {
	}

	private Person x;

	private Person y;

	private Person z;

	@BeforeEach
	void threePeopleWithTwoRolesEach() {
		UUID xAccount = JobContent.member(this.jdbc, "Phạm Hải X");
		UUID yAccount = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Y", List.of(TeamTestSupport.REACT), "React");
		IdentityTestSupport.verifyStudent(this.jdbc, yAccount, LocalDate.of(2026, 9, 30));
		UUID zAccount = JobContent.member(this.jdbc, "Lê Thảo Z");
		JobWorld a = new JobWorld(this.jdbc, this::upload, "A", xAccount, true, mentor("A"), null, null);
		JobWorld b = new JobWorld(this.jdbc, this::upload, "B", customer(), false, mentor("B"), xAccount, null);
		JobWorld c = new JobWorld(this.jdbc, this::upload, "C", customer(), false, yAccount, null, null);
		JobWorld d = new JobWorld(this.jdbc, this::upload, "D", customer(), false, mentor("D"), yAccount, null);
		JobWorld e = new JobWorld(this.jdbc, this::upload, "E", customer(), false, mentor("E"), null, zAccount);
		JobWorld f = new JobWorld(this.jdbc, this::upload, "F", zAccount, true, mentor("F"), null, null);
		this.x = new Person("X", xAccount, ordered(a, Held.CUSTOMER, b, Held.ACTIVE_CREW));
		this.y = new Person("Y", yAccount, ordered(c, Held.MENTOR, d, Held.ACTIVE_CREW));
		this.z = new Person("Z", zAccount, ordered(e, Held.FORMER_CREW, f, Held.CUSTOMER));
	}

	@Test
	void eachPersonHoldsOneRoleInEachOfTheirJobs() {
		for (Person person : people()) {
			person.jobs().forEach((job, held) -> {
				JobRole role = this.roles.roleOf(job.job, person.account());
				switch (held) {
					case CUSTOMER -> assertThat(role).as(person.name() + " in " + job.tag).isEqualTo(JobRole.CUSTOMER);
					case MENTOR -> assertThat(role).as(person.name() + " in " + job.tag).isEqualTo(JobRole.MENTOR);
					case ACTIVE_CREW -> assertThat(this.crews.crewRelationship(job.job, person.account()).kind())
						.as(person.name() + " in " + job.tag)
						.isEqualTo(Crews.Relationship.ACTIVE);
					case FORMER_CREW -> assertThat(this.crews.crewRelationship(job.job, person.account()).kind())
						.as(person.name() + " in " + job.tag)
						.isEqualTo(Crews.Relationship.FORMER);
				}
			});
		}
	}

	@Test
	void noContextLeaksIntoTheOtherThroughAnyJobScopedRead() throws Exception {
		List<String> endpoints = jobScopedReads();
		assertThat(endpoints).as("the matrix's Job-scoped reads").hasSizeGreaterThan(40);
		List<String> leaks = new ArrayList<>();
		Map<String, Set<Integer>> answers = new LinkedHashMap<>();
		for (Person person : people()) {
			for (Map.Entry<JobWorld, Held> through : person.jobs().entrySet()) {
				Set<String> forbidden = forbiddenThrough(person, through.getKey());
				for (String endpoint : endpoints) {
					String path = expand(endpoint, through.getKey());
					MockHttpServletResponse response = read(person, path);
					answers.computeIfAbsent(endpoint, key -> new TreeSet<>()).add(response.getStatus());
					leaks(person.name() + " through " + through.getKey().tag + ": GET " + endpoint, path, response, forbidden,
							leaks);
					// The path names what the window never saw: the read itself must find nothing (review iteration 4).
					if (through.getValue() == Held.FORMER_CREW && response.getStatus() != 404 && through.getKey()
						.contentAfterTheWindow()
						.stream()
						.anyMatch(path::contains)) {
						leaks.add(person.name() + " through " + through.getKey().tag + ": GET " + endpoint + " answered "
								+ response.getStatus() + " on what was written after the window, not 404");
					}
				}
			}
		}
		assertThat(leaks).isEmpty();
		assertThat(NOT_SWEPT.keySet()
			.stream()
			.filter(endpoint -> answers.getOrDefault(endpoint, Set.of()).stream().anyMatch(CrossRoleIT::pastThePolicy))
			.map(endpoint -> endpoint + " answered " + answers.get(endpoint))
			.toList()).as("NOT_SWEPT reads some person now gets past: take them off the list").isEmpty();
		// A read no person gets past says nothing about leaks (review iteration 3, B5).
		assertThat(answers.entrySet()
			.stream()
			.filter(entry -> !NOT_SWEPT.containsKey(entry.getKey()))
			.filter(entry -> entry.getValue().stream().noneMatch(CrossRoleIT::pastThePolicy))
			.map(entry -> entry.getKey() + " answered only " + entry.getValue())
			.toList()).as("swept reads no person gets past: seed their object, or list them in NOT_SWEPT with the reason")
			.isEmpty();
	}

	/**
	 * The Crew's own reads of a Job (AD-41, review iteration 4, V1): through every Job where the person is neither its
	 * Mentor nor on its Crew, X through A and Z through F, each finds nothing; the Mentor and an active member read
	 * them.
	 */
	@Test
	void theCrewsReadsRefuseEveryJobWhereThePersonIsNeitherMentorNorCrew() throws Exception {
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (Person person : people()) {
			for (Map.Entry<JobWorld, Held> through : person.jobs().entrySet()) {
				if (through.getValue() != Held.CUSTOMER) {
					continue;
				}
				for (String endpoint : CREW_ONLY) {
					status(person, through.getKey(), endpoint, read(person, expand(endpoint, through.getKey())), 404, wrong);
					checked++;
				}
			}
		}
		assertThat(wrong).isEmpty();
		assertThat(CREW_ONLY).as("the Crew's reads of a Job").hasSizeGreaterThanOrEqualTo(5)
			.contains("/api/v1/jobs/{jobId}/crew-chat", "/api/v1/jobs/{jobId}/shared-pages");
		assertThat(checked).as("X through A, Z through F").isEqualTo(2 * CREW_ONLY.size());
		for (String endpoint : CREW_ONLY) {
			assertThat(read(this.x, expand(endpoint, job(this.x, "B"))).getStatus() / 100)
				.as("X is on B's Crew: " + endpoint)
				.isEqualTo(2);
		}
	}

	/**
	 * The Mentor's own reads of a Job (AD-43, review iteration 3, B7): through every Job where the person is not the
	 * Mentor, X's two, Y's Crew Job and Z's two, each finds nothing, so a policy that admits a Mentor of any Job fails
	 * through D.
	 */
	@Test
	void theMentorsReadsRefuseEveryJobWhereThePersonIsNotItsMentor() throws Exception {
		assertThat(MENTOR_ONLY).as("the Mentor's reads of a Job").hasSizeGreaterThan(2);
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (Person person : people()) {
			for (Map.Entry<JobWorld, Held> through : person.jobs().entrySet()) {
				if (through.getValue() == Held.MENTOR) {
					continue;
				}
				for (String endpoint : MENTOR_ONLY) {
					status(person, through.getKey(), endpoint, read(person, expand(endpoint, through.getKey())), 404, wrong);
					checked++;
				}
			}
		}
		assertThat(wrong).isEmpty();
		assertThat(checked).as("X through A and B, Y through D, Z through E and F").isEqualTo(5 * MENTOR_ONLY.size());
		for (String endpoint : MENTOR_ONLY) {
			JobWorld c = job(this.y, "C");
			assertThat(read(this.y, expand(endpoint, c)).getStatus()).as("Y reads C's " + endpoint).isEqualTo(200);
		}
	}

	@Test
	void eachRoleReadsItsOwnJob() throws Exception {
		List<String> wrong = new ArrayList<>();
		for (Person person : people()) {
			for (Map.Entry<JobWorld, Held> through : person.jobs().entrySet()) {
				for (String endpoint : READS.get(through.getValue())) {
					status(person, through.getKey(), endpoint, read(person, expand(endpoint, through.getKey())), 200, wrong);
				}
			}
		}
		assertThat(wrong).isEmpty();
		JobWorld a = job(this.x, "A");
		JobWorld b = job(this.x, "B");
		assertThat(body(this.x, "/api/v1/jobs/" + a.job + "/chat/messages")).contains(a.canary("job-chat"));
		assertThat(body(this.x, "/api/v1/crew-chats/" + b.crewChat + "/messages")).contains(b.canary("crew-chat"));
		JobWorld c = job(this.y, "C");
		JobWorld d = job(this.y, "D");
		assertThat(body(this.y, "/api/v1/crew-chats/" + c.crewChat + "/messages")).contains(c.canary("crew-chat"));
		assertThat(body(this.y, "/api/v1/jobs/" + c.job + "/chat/messages")).contains(c.canary("job-chat"));
		assertThat(body(this.y, "/api/v1/crew-chats/" + d.crewChat + "/messages")).contains(d.canary("crew-chat"));
		JobWorld e = job(this.z, "E");
		JobWorld f = job(this.z, "F");
		assertThat(body(this.z, "/api/v1/crew-chats/" + e.crewChat + "/messages")).as("the history up to the window's end")
			.contains(e.canary("early-chat"));
		assertThat(body(this.z, "/api/v1/jobs/" + f.job + "/chat/messages")).contains(f.canary("job-chat"));
		// Both Page filters keep something: the Customer's Page for the Customer, the window's Page for the former member.
		assertThat(body(this.x, "/api/v1/boards/" + a.job + "/pages")).as("the Customer's Page")
			.contains(a.canary("customer-page"));
		assertThat(body(this.z, "/api/v1/boards/" + e.job + "/pages")).as("the Page written inside the window")
			.contains(e.canary("early-page"));
		// The release's parts the sweep looks for are there to find: each member's own, and the Crew's for the Mentor.
		assertThat(body(this.x, "/api/v1/balance")).as("X's part of B's release").contains(b.ownSplit());
		assertThat(body(this.y, "/api/v1/balance")).as("the Crew's part of C's release, for its Mentor")
			.contains(c.canary("crew-split"));
		assertThat(body(this.y, "/api/v1/balance")).as("Y's part of D's release").contains(d.ownSplit());
		assertThat(body(this.z, "/api/v1/balance")).as("Z's part of E's release, earned in the window")
			.contains(e.ownSplit());
	}

	/**
	 * The busy marker (Story 7.2) and the cancellation tab (FR-40) tell each reader the role it reads as: through each
	 * of a person's Jobs they answer the role held there, never the role of their other Job; a former member, whose
	 * Job it no longer is, finds nothing.
	 */
	@Test
	void theBusyMarkerReadsEachJobAsTheRoleHeldThere() throws Exception {
		Map<Held, String> audience = Map.of(Held.CUSTOMER, "CUSTOMER", Held.MENTOR, "MENTOR", Held.ACTIVE_CREW, "CREW");
		List<String> wrong = new ArrayList<>();
		for (Person person : people()) {
			for (Map.Entry<JobWorld, Held> through : person.jobs().entrySet()) {
				for (String endpoint : List.of("/api/v1/jobs/{jobId}/busy", "/api/v1/boards/{boardId}/cancellation")) {
					MockHttpServletResponse response = read(person, expand(endpoint, through.getKey()));
					String expected = audience.get(through.getValue());
					if (expected == null) {
						status(person, through.getKey(), "GET " + endpoint, response, 404, wrong);
						continue;
					}
					status(person, through.getKey(), "GET " + endpoint, response, 200, wrong);
					if (response.getStatus() == 200) {
						String got = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8))
							.path("audience")
							.asString();
						if (!expected.equals(got)) {
							wrong.add(person.name() + " through " + through.getKey().tag + ": GET " + endpoint
									+ " $.audience is " + got + ", not " + expected);
						}
					}
				}
			}
		}
		assertThat(wrong).isEmpty();
	}

	@Test
	void theCustomersReadsAndCommandsRefuseEveryOtherRoleWithNotFound() throws Exception {
		List<String> wrong = new ArrayList<>();
		assertThat(CUSTOMER_ONLY).as("the Customer's reads of a Job").hasSizeGreaterThan(4);
		for (Person person : people()) {
			for (Map.Entry<JobWorld, Held> through : person.jobs().entrySet()) {
				JobWorld job = through.getKey();
				Held held = through.getValue();
				if (held != Held.CUSTOMER) {
					for (String endpoint : CUSTOMER_ONLY) {
						status(person, job, endpoint, read(person, expand(endpoint, job)), 404, wrong);
					}
					status(person, job, "POST /api/v1/jobs/{jobId}/withdraw",
							command(person, post("/api/v1/jobs/" + job.job + "/withdraw")), 404, wrong);
				}
				if (held == Held.CUSTOMER || held == Held.MENTOR) {
					status(person, job, "POST /api/v1/crew/jobs/{jobId}/decline", command(person,
							post("/api/v1/crew/jobs/" + job.job + "/decline").contentType(MediaType.APPLICATION_JSON)
								.content("{\"crewTermsVersionNo\":1}")),
							404, wrong);
				}
			}
		}
		assertThat(wrong).isEmpty();
	}

	@Test
	void noContextLeaksIntoTheOtherThroughTheGlobalReadsOrTheCustomersContact() throws Exception {
		List<String> leaks = new ArrayList<>();
		List<String> wrong = new ArrayList<>();
		List<JobWorld> everyJob = people().stream().flatMap(person -> person.jobs().keySet().stream()).toList();
		for (Person person : people()) {
			Set<String> forbidden = new LinkedHashSet<>();
			person.jobs().forEach((job, held) -> forbidden.addAll(hiddenBy(held, job)));
			// Everything of every Job the person holds no relation to (review iteration 4).
			everyJob.stream().filter(job -> !person.jobs().containsKey(job)).forEach(job -> forbidden.addAll(job.all()));
			for (String path : GLOBAL_READS) {
				MockHttpServletResponse response = read(person, path);
				if (response.getStatus() != 200) {
					wrong.add(person.name() + ": GET " + path + " answered " + response.getStatus());
				}
				leaks(person.name() + ": GET " + path, path, response, forbidden, leaks);
			}
			for (Map.Entry<JobWorld, Held> job : person.jobs().entrySet()) {
				String contact = "/api/v1/customers/" + job.getKey().customer + "/contact";
				leaks(person.name() + " to " + job.getKey().tag + "'s Customer: GET " + contact, contact,
						read(person, contact), forbiddenThrough(person, job.getKey()), leaks);
			}
		}
		assertThat(wrong).isEmpty();
		assertThat(leaks).isEmpty();
	}

	/**
	 * What the person may not read through {@code job}: everything of their other Job, its canaries and the IDs of its
	 * objects (review iteration 3, B8), and what their role in {@code job} hides.
	 */
	private static Set<String> forbiddenThrough(Person person, JobWorld job) {
		Set<String> forbidden = new LinkedHashSet<>(hiddenBy(person.jobs().get(job), job));
		person.jobs().keySet().stream().filter(other -> other != job).forEach(other -> {
			forbidden.addAll(other.all());
			forbidden.addAll(other.objectIds());
		});
		return forbidden;
	}

	/**
	 * What a role hides of its own Job (AD-20, AD-41, {@code canaries.csv}): the Crew's, their split included, from
	 * the Customer; the Customer's and the other members' parts from the Crew; and from a former member also the
	 * Crew's identity and what was written after their window ended, its IDs included (review iteration 3, E13).
	 */
	private static Set<String> hiddenBy(Held held, JobWorld job) {
		Set<String> hidden = new LinkedHashSet<>();
		hidden.add(job.canary("crew-email"));
		switch (held) {
			case CUSTOMER -> {
				hidden.addAll(job.crewIdentity());
				hidden.addAll(job.crewContent());
				hidden.addAll(job.crewSplit());
				// The team-only Pages and the Work Items, whose titles the Customer's screens never name (AD-39).
				hidden.add(job.canary("page-title"));
				hidden.add(job.canary("item-title"));
				hidden.add(job.canary("early-page"));
			}
			case MENTOR -> {
			}
			case ACTIVE_CREW -> {
				hidden.addAll(job.customerData());
				hidden.addAll(job.coSplit());
			}
			case FORMER_CREW -> {
				hidden.addAll(job.customerData());
				hidden.addAll(job.crewIdentity());
				hidden.addAll(job.crewContent());
				hidden.addAll(job.coSplit());
				hidden.addAll(job.writtenAfterTheWindow());
				// Written after the window ended: the Work Item's and the Page's titles, and the Customer-visible Page.
				hidden.add(job.canary("item-title"));
				hidden.add(job.canary("page-title"));
				hidden.add(job.canary("customer-page"));
			}
		}
		return hidden;
	}

	/**
	 * Records every forbidden canary the answer holds, and a server error, which shows nothing either way. An ID the
	 * request's {@code path} names is the caller's already, so an answer that repeats it leaks nothing.
	 */
	private static void leaks(String what, String path, MockHttpServletResponse response, Set<String> forbidden,
			List<String> leaks) throws Exception {
		if (response.getStatus() >= 500) {
			leaks.add(what + " answered " + response.getStatus());
		}
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		for (String canary : forbidden) {
			if (path.contains(canary)) {
				continue;
			}
			boolean inHeaders = response.getHeaderNames()
				.stream()
				.anyMatch(header -> header.contains(canary)
						|| response.getHeaders(header).stream().anyMatch(value -> value.contains(canary)));
			if (body.contains(canary) || inHeaders) {
				leaks.add(what + " (" + response.getStatus() + ") holds " + canary);
			}
		}
	}

	/** Whether a person got past the read's policy: a {@code 2xx}, or a {@code 409} or {@code 422} about the state. */
	private static boolean pastThePolicy(int status) {
		return (status >= 200 && status < 300) || status == 409 || status == 422;
	}

	private static void status(Person person, JobWorld job, String endpoint, MockHttpServletResponse response,
			int expected, List<String> wrong) {
		if (response.getStatus() != expected) {
			wrong.add(person.name() + " through " + job.tag + ": " + endpoint + " answered " + response.getStatus()
					+ ", not " + expected);
		}
	}

	/**
	 * Every {@code {jobId}} {@code GET} of the matrix whose crew-Job rows let the Customer past (a {@code 2xx}) and
	 * refuse the Mentor and every Crew state.
	 */
	private static List<String> customerOnly() {
		Map<String, List<MatrixRow>> byPath = new LinkedHashMap<>();
		for (MatrixRow row : MatrixRow.loadMatrix()) {
			if (row.method() == HttpMethod.GET && row.path().contains("{jobId}") && row.binds(MatrixRow.Binding.CREW_JOB)) {
				byPath.computeIfAbsent(row.path(), key -> new ArrayList<>()).add(row);
			}
		}
		Set<MatrixCaller> others = EnumSet.of(MatrixCaller.MENTOR, MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_ACTIVE,
				MatrixCaller.CREW_FORMER, MatrixCaller.CROSS_ROLE);
		List<String> paths = new ArrayList<>();
		byPath.forEach((path, rows) -> {
			boolean customer = rows.stream()
				.anyMatch(row -> row.caller() == MatrixCaller.CUSTOMER && row.expect().status() / 100 == 2);
			boolean nobodyElse = rows.stream()
				.filter(row -> others.contains(row.caller()))
				.allMatch(row -> row.expect().status() == 404);
			if (customer && nobodyElse) {
				paths.add(path);
			}
		});
		return List.copyOf(paths);
	}

	/**
	 * Every {@code {jobId}} or {@code {boardId}} {@code GET} of the matrix whose crew-Job rows let the active member past
	 * and refuse the Customer with {@code NOT_FOUND}.
	 */
	private static List<String> crewOnly() {
		Map<String, List<MatrixRow>> byPath = new LinkedHashMap<>();
		for (MatrixRow row : MatrixRow.loadMatrix()) {
			boolean jobOrBoard = row.path().contains("{jobId}") || row.path().contains("{boardId}");
			boolean crewJob = row.path().contains("{jobId}") ? row.binds(MatrixRow.Binding.CREW_JOB) : row.state().isEmpty();
			if (row.method() == HttpMethod.GET && jobOrBoard && crewJob) {
				byPath.computeIfAbsent(row.path(), key -> new ArrayList<>()).add(row);
			}
		}
		List<String> paths = new ArrayList<>();
		byPath.forEach((path, rows) -> {
			boolean active = rows.stream()
				.anyMatch(row -> row.caller() == MatrixCaller.CREW_ACTIVE && row.expect().status() / 100 == 2);
			boolean customerRefused = rows.stream()
				.filter(row -> row.caller() == MatrixCaller.CUSTOMER)
				.allMatch(row -> row.expect().status() == 404);
			boolean customerRow = rows.stream().anyMatch(row -> row.caller() == MatrixCaller.CUSTOMER);
			if (active && customerRow && customerRefused) {
				paths.add(path);
			}
		});
		return List.copyOf(paths);
	}

	private static Map<String, String> notSwept() {
		Map<String, String> notSwept = new LinkedHashMap<>();
		notSwept.put("/api/v1/jobs/{jobId}/final-merge", "the Final Merge authorization opens with a closure proposal or"
				+ " at J8 (Story 8.7), and every Job of the sweep is Active with neither");
		for (MatrixRow row : MatrixRow.loadMatrix()) {
			if (row.method() == HttpMethod.GET && row.path().startsWith("/api/v1/admin/")) {
				notSwept.putIfAbsent(row.path(), "an Administrator's read: none of the three people is an Administrator");
			}
		}
		return Map.copyOf(notSwept);
	}

	/** Every {@code GET} endpoint of the matrix whose path names a Job or an object of one. */
	private static List<String> jobScopedReads() {
		Set<String> endpoints = new LinkedHashSet<>();
		for (MatrixRow row : MatrixRow.loadMatrix()) {
			if (row.method() == HttpMethod.GET && !row.retiredTeam()
					&& MatrixRow.placeholders(row.path()).stream().anyMatch(MatrixRow.JOB_SCOPED::contains)) {
				endpoints.add(row.path());
			}
		}
		return List.copyOf(endpoints);
	}

	private static String expand(String template, JobWorld job) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder expanded = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(expanded, Matcher.quoteReplacement(job.value(matcher.group(1))));
		}
		matcher.appendTail(expanded);
		return expanded.toString();
	}

	private MockHttpServletResponse read(Person person, String path) throws Exception {
		return this.mvc.perform(get(path).with(signedIn(person))).andReturn().getResponse();
	}

	private MockHttpServletResponse command(Person person, MockHttpServletRequestBuilder request) throws Exception {
		return this.mvc.perform(request.with(signedIn(person)).with(csrf())).andReturn().getResponse();
	}

	private String body(Person person, String path) throws Exception {
		MockHttpServletResponse response = read(person, path);
		assertThat(response.getStatus()).as(person.name() + ": GET " + path).isEqualTo(200);
		return response.getContentAsString(StandardCharsets.UTF_8);
	}

	private RequestPostProcessor signedIn(Person person) {
		return IdentityTestSupport.signedIn(this.sessions, person.account(), Role.STUDENT, this.clock.instant());
	}

	private List<Person> people() {
		return List.of(this.x, this.y, this.z);
	}

	private static JobWorld job(Person person, String tag) {
		return person.jobs().keySet().stream().filter(job -> job.tag.equals(tag)).findFirst().orElseThrow();
	}

	/** A PDF stored through {@code files} for {@code purpose}, as the owning module stores an upload. */
	private UUID upload(String purpose, UUID author, String fileName) {
		PreparedFile prepared = this.files.prepare(new FileUpload(purpose, author, fileName, TestFiles.pdf(),
				Set.of(FileKind.PDF)));
		return Objects.requireNonNull(new TransactionTemplate(this.transactionManager)
			.execute(status -> this.files.store(prepared).id()));
	}

	private UUID customer() {
		return IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
	}

	private UUID mentor(String tag) {
		return TeamTestSupport.mentor(this.jdbc, "Người hướng dẫn " + tag, List.of(TeamTestSupport.REACT), "React");
	}

	private static Map<JobWorld, Held> ordered(JobWorld first, Held firstRole, JobWorld second, Held secondRole) {
		Map<JobWorld, Held> jobs = new LinkedHashMap<>();
		jobs.put(first, firstRole);
		jobs.put(second, secondRole);
		return jobs;
	}

	/**
	 * One Active Job with its canaries, each {@code xr-{tag}-{kind}}: a Crew member who is no one of the three
	 * people, whose shown name, email and LinkedIn link are canaries, the Crew Chat, a Work Item with a note and an
	 * Attachment, and a Page, all written by the Mentor after the former member's window ended; the Job Chat; and
	 * the Customer's contact email, unless the Customer is one of the three people (their own data). A former
	 * member's Crew Chat also holds a message from before their window's end. Its first Milestone was accepted and
	 * released under Crew Terms version 1, and the Crew's parts of it are canary amounts. It also holds one object of
	 * its own for every placeholder the sweep fills: the second Milestone and its criterion, Funded and paid, a
	 * handover, a Change Request, an open Dispute that froze the Job, a share Team Issue and an Abuse Report of the
	 * other Crew member, the selected bid, an External Access item, a screenshot, the Attachment's stored file, and the
	 * Customer's next Job on the Product, an Open Job Post to which they invited the Job's Mentor.
	 */
	static final class JobWorld {

		final String tag;

		final UUID job;

		final UUID customer;

		final UUID crewChat;

		private final boolean customerIsAPerson;

		/** The other member's part of the first Milestone's release, in VND: only the Mentor reads another's part. */
		private long coPart;

		/** The person's own part of it, when they are on the Crew: hidden from the Customer, theirs to read. */
		private @Nullable Long ownPart;

		/** The IDs of the Job's objects another Job's reader may never see (review iteration 3, B8). */
		private final Set<String> objectIds = new LinkedHashSet<>();

		/** The IDs of what was written after the former member's window ended (review iteration 3, E13). */
		private final Set<String> writtenAfterTheWindow = new LinkedHashSet<>();

		/** The Job content among them, which a former member's direct read of finds nothing (its public post aside). */
		private final Set<String> contentAfterTheWindow = new LinkedHashSet<>();

		private final Map<String, String> values = new HashMap<>();

		JobWorld(JdbcClient jdbc, Uploads uploads, String tag, UUID customer, boolean customerIsAPerson, UUID mentor,
				@Nullable UUID activeMember, @Nullable UUID formerMember) {
			this.tag = tag;
			this.customer = customer;
			this.customerIsAPerson = customerIsAPerson;
			UUID product = JobContent.product(jdbc, customer, "Sản phẩm " + tag);
			this.job = JobContent.activeJob(jdbc, product, customer, mentor, "Việc " + tag);
			Timestamp signed = Timestamp.from(JobContent.SIGNED);
			Timestamp written = Timestamp.from(WRITTEN);
			if (!customerIsAPerson) {
				JobContent.customerProfile(jdbc, customer, "Khách hàng " + tag, canary("contact"));
			}
			UUID coMember = JobContent.canaryMember(jdbc, canary("crew-name"), canary("crew-email"), canary("crew-link"), null);
			UUID coRow = JobContent.crewRow(jdbc, this.job, coMember, "ACTIVE", null);
			UUID activeRow = activeMember == null ? null : JobContent.crewRow(jdbc, this.job, activeMember, "ACTIVE", null);
			UUID formerRow = formerMember == null ? null : JobContent.crewRow(jdbc, this.job, formerMember, "LEFT", WINDOW_END);
			this.crewChat = JobContent.chat(jdbc, this.job, product, "CREW");
			if (formerMember != null) {
				JobContent.message(jdbc, this.crewChat, mentor, canary("early-chat"), WINDOW_END.minus(Duration.ofDays(2)));
			}
			UUID message = JobContent.message(jdbc, this.crewChat, mentor, canary("crew-chat"), WRITTEN);
			UUID jobChat = JobContent.chat(jdbc, this.job, product, "JOB");
			JobContent.message(jdbc, jobChat, mentor, canary("job-chat"), WRITTEN);
			// Two Milestones pinned to Crew Terms version 1: the first accepted and released before the former member's
			// window ended, the second Funded and handed over.
			UUID released = UUID.randomUUID();
			UUID milestone = UUID.randomUUID();
			UUID criterion = UUID.randomUUID();
			UUID handover = UUID.randomUUID();
			UUID releasedHandover = UUID.randomUUID();
			long base = 52_731_469L + 1_234_567L * (tag.charAt(0) - 'A');
			String content = """
					{"scope":"Sửa lỗi","priceVnd":%d,"codeJob":true,"assessment":false,"skills":[],"externalAccess":[],
					 "milestones":[{"id":"%s","title":"Thiết lập","priceVnd":%d,"dueOn":"2026-11-01","revisionRounds":2,
					   "criteria":[{"id":"%s","statement":"Khách đặt được bàn","checkSteps":"1. Mở."}]},
					  {"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"2026-11-20",
					   "revisionRounds":2,"criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn",
					   "checkSteps":"1. Mở. 2. Đặt."}]}]}
					""".formatted(base + 800_000, released, base, UUID.randomUUID(), milestone, criterion);
			jdbc.sql("""
					INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
					VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :signed)
					""").param("job", this.job).param("content", content).param("mentor", mentor).param("signed", signed)
				.update();
			jdbc.sql("""
					INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
					    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
					VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :customer, :signed, :mentor, :signed)
					""").param("job", this.job).param("content", content).param("customer", customer).param("mentor", mentor)
				.param("signed", signed).update();
			jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :id").param("id", this.job).update();
			// The live draft the Agreement started with, which stays the Mentor's to read (FR-16).
			jdbc.sql("""
					INSERT INTO job.agreement_draft (job_id, scope, price_vnd, revision, saved_at)
					VALUES (:job, 'Sửa lỗi', :price, 1, :signed)
					""").param("job", this.job).param("price", base + 800_000).param("signed", signed).update();
			// The person's own row, active or former, holds 35 %, the other member 40 % (70 % alone), the Mentor the rest.
			UUID ownMember = activeMember != null ? activeMember : formerMember;
			UUID ownRow = activeMember != null ? activeRow : formerRow;
			int coPercent = ownMember == null ? 70 : 40;
			JobContent.crewTerms(jdbc, this.job, mentor, JobContent.SIGNED);
			JobContent.share(jdbc, this.job, mentor, null, 100 - coPercent - (ownMember == null ? 0 : 35));
			JobContent.share(jdbc, this.job, coMember, coRow, coPercent);
			if (ownMember != null) {
				JobContent.share(jdbc, this.job, ownMember, ownRow, 35);
			}
			Instant funded = JobContent.SIGNED.plus(Duration.ofDays(1));
			Instant accepted = JobContent.SIGNED.plus(Duration.ofDays(3));
			jdbc.sql("""
					INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
					    funded_at, crew_terms_version_no, submitted_at, handover_id, accepted_at)
					VALUES (:id, :job, 0, 'Thiết lập', :price, DATE '2026-11-01', 2, :signed, 'ACCEPTED', :funded, 1, :submitted,
					    :handover, :accepted)
					""").param("id", released).param("job", this.job).param("price", base).param("signed", signed)
				.param("funded", Timestamp.from(funded)).param("submitted", Timestamp.from(accepted.minus(Duration.ofDays(1))))
				.param("handover", releasedHandover).param("accepted", Timestamp.from(accepted)).update();
			jdbc.sql("""
					INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
					    funded_at, crew_terms_version_no, submitted_at, handover_id)
					VALUES (:id, :job, 1, 'Sửa lỗi trùng giờ', 800000, DATE '2026-11-20', 2, :signed, 'SUBMITTED', :funded, 1,
					    :submitted, :handover)
					""").param("id", milestone).param("job", this.job).param("signed", signed)
				.param("funded", Timestamp.from(accepted)).param("submitted", written).param("handover", handover).update();
			JobContent.fund(jdbc, this.job, product, released, customer, base, funded);
			UUID order = JobContent.fund(jdbc, this.job, product, milestone, customer, 800_000, accepted);
			UUID transition = UUID.randomUUID();
			long coPart = base * coPercent / 100;
			long ownPart = ownMember == null ? 0 : base * 35 / 100;
			this.coPart = coPart;
			this.ownPart = ownMember == null ? null : ownPart;
			JobContent.release(jdbc, "RELEASE", transition, this.job, product, released, mentor, base - coPart - ownPart,
					100 - coPercent - (ownMember == null ? 0 : 35), base, accepted);
			JobContent.release(jdbc, "RELEASE", transition, this.job, product, released, coMember, coPart, coPercent, base,
					accepted);
			if (ownMember != null) {
				JobContent.release(jdbc, "RELEASE", transition, this.job, product, released, ownMember, ownPart, 35, base,
						accepted);
			}
			jdbc.sql("""
					INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title,
					    round, mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at,
					    decided_by)
					VALUES (:id, :job, :product, :milestone, 1, 'Thiết lập', 1, :mentor, 'ACCEPTED', 3, :transition, :at, :at,
					    :decided, :customer)
					""").param("id", releasedHandover).param("job", this.job).param("product", product)
				.param("milestone", released).param("mentor", mentor).param("transition", UUID.randomUUID())
				.param("at", Timestamp.from(accepted.minus(Duration.ofDays(1)))).param("decided", Timestamp.from(accepted))
				.param("customer", customer).update();
			JobContent.board(jdbc, this.job, product, "X" + tag);
			UUID item = JobContent.workItem(jdbc, this.job, 1, canary("item-title"), milestone, criterion, WRITTEN);
			UUID note = JobContent.note(jdbc, item, this.job, mentor, canary("note"), WRITTEN);
			// The Attachment's file, stored as work stores it: {fileId} is the Job's own file (review iteration 4, G7).
			UUID file = uploads.store("work.attachment", mentor, canary("attachment") + ".pdf");
			UUID attachment = JobContent.attachment(jdbc, item, this.job, mentor, canary("attachment") + ".pdf", file,
					WRITTEN);
			UUID page = JobContent.page(jdbc, this.job, mentor, canary("page-title"), canary("page"), WRITTEN);
			// A Page the Customer reads, written after the window, and a team-only one written inside it.
			UUID customerPage = JobContent.page(jdbc, this.job, mentor, canary("customer-page"), canary("customer-page"),
					WRITTEN);
			jdbc.sql("UPDATE work.page SET visibility = 'CUSTOMER' WHERE id = :id").param("id", customerPage).update();
			JobContent.page(jdbc, this.job, mentor, canary("early-page"), canary("early-page"),
					WINDOW_END.minus(Duration.ofDays(2)));
			jdbc.sql("""
					INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title,
					    round, mentor_account_id, status, job_version, transition_id, check_by, created_at)
					VALUES (:id, :job, :product, :milestone, 2, 'Sửa lỗi trùng giờ', 1, :mentor, 'SUBMITTED', 5, :transition,
					    :at, :at)
					""").param("id", handover).param("job", this.job).param("product", product).param("milestone", milestone)
				.param("mentor", mentor).param("transition", UUID.randomUUID()).param("at", written).update();
			// No follow-up: only an Assessment's accepted Report has them (FR-11), and no read names one (G9).
			UUID changeRequest = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO job.change_request (id, job_id, proposer, proposer_account_id, description, items, status,
					    created_at)
					VALUES (:id, :job, 'CUSTOMER', :customer, 'Cần thêm thời gian', CAST(:items AS JSONB), 'OPEN', :at)
					""")
				.param("id", changeRequest)
				.param("job", this.job)
				.param("customer", customer)
				.param("items", "[{\"milestoneId\":\"" + milestone + "\",\"number\":2,\"title\":\"Sửa lỗi trùng giờ\","
						+ "\"oldDueOn\":\"2026-11-20\",\"newDueOn\":\"2027-01-20\"}]")
				.param("at", written)
				.update();
			// The Customer's open Dispute about the held Milestone froze the Job, as Disputes.open does (G9): the open
			// Change Request stays open beside it, since only M5, M8, J8 or J10 lapse one.
			UUID dispute = UUID.randomUUID();
			Timestamp opened = Timestamp.from(WRITTEN.plus(Duration.ofHours(2)));
			jdbc.sql("""
					INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern,
					    milestone_id, job_state, status, opened_at, response_days, statement_due_at)
					VALUES (:id, :job, :product, 'CUSTOMER', :customer, 'Giai đoạn chưa đạt', 'HELD_MILESTONE', :milestone,
					    'ACTIVE', 'OPEN', :at, 5, :due)
					""").param("id", dispute).param("job", this.job).param("product", product).param("customer", customer)
				.param("milestone", milestone).param("at", opened)
				.param("due", Timestamp.from(WRITTEN.plus(Duration.ofHours(2)).plus(Duration.ofDays(5)))).update();
			jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :id").param("at", opened).param("id", this.job).update();
			jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
				.param("job", this.job)
				.param("at", opened)
				.update();
			// The other member's share Team Issue on the held Milestone: a running Job has no Rating yet (G9).
			UUID teamIssue = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number,
					    raiser_account_id, mentor_account_id, reason, phase, answer_days, escalation_days, raised_at,
					    answer_due_at)
					VALUES (:id, :job, :product, 'SHARE', :milestone, 2, :raiser, :mentor, 'Phần chia chưa đúng', 'ANSWER', 5, 5,
					    :at, :due)
					""").param("id", teamIssue).param("job", this.job).param("product", product)
				.param("milestone", milestone).param("raiser", coMember).param("mentor", mentor).param("at", written)
				.param("due", Timestamp.from(WRITTEN.plus(Duration.ofDays(5)))).update();
			jdbc.sql("""
					INSERT INTO payment.share_hold (team_issue_id, milestone_id, job_id, product_id, raiser_account_id, held_at)
					VALUES (:issue, :milestone, :job, :product, :raiser, :at)
					""").param("issue", teamIssue).param("milestone", milestone).param("job", this.job)
				.param("product", product).param("raiser", coMember).param("at", written).update();
			UUID report = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO admin.abuse_report (id, reporter_account_id, kind, target_id, job_id, product_id,
					    author_account_id, label, reason, status, created_at)
					VALUES (:id, :reporter, 'CREW_CHAT_MESSAGE', :message, :job, :product, :mentor, :label, 'Lời lẽ xúc phạm',
					    'OPEN', :at)
					""").param("id", report).param("reporter", coMember).param("message", message).param("job", this.job)
				.param("product", product).param("mentor", mentor).param("label", "Việc " + tag).param("at", written)
				.update();
			UUID application = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
					    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
					VALUES (:id, :job, :product, :customer, :mentor, 'SELECTED', 800000, 7, 'Cách làm', '1.0', :at)
					""").param("id", application).param("job", this.job).param("product", product)
				.param("customer", customer).param("mentor", mentor).param("at", signed).update();
			jdbc.sql("UPDATE job.job SET selected_application_id = :application WHERE id = :id")
				.param("application", application).param("id", this.job).update();
			UUID access = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO job.external_access (id, job_id, position, system, purpose, created_at)
					VALUES (:id, :job, 0, 'Bảng quản trị', 'Xem lỗi', :at)
					""").param("id", access).param("job", this.job).param("at", signed).update();
			UUID screenshot = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
					VALUES (:job, :file, 'man-hinh.png', 100, :at)
					""").param("job", this.job).param("file", screenshot).param("at", signed).update();
			// The Customer's next Job on the Product, an Open Job Post, to which they invited this Job's Mentor (FR-14):
			// the post reads name it (review iteration 3, B5).
			UUID post = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
					    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at,
					    updated_at)
					VALUES (:id, :product, :customer, 'OPEN', :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
					    DATE '2026-12-01', '{}'::jsonb, 14, :at, :lapses, 1, :at, :at)
					""")
				.param("id", post)
				.param("product", product)
				.param("customer", customer)
				.param("title", "Tin " + tag)
				.param("at", written)
				.param("lapses", Timestamp.from(WRITTEN.plus(Duration.ofDays(14))))
				.update();
			UUID invitation = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO matching.invitation (id, job_id, product_id, customer_account_id, mentor_account_id, status,
					    invited_at)
					VALUES (:id, :job, :product, :customer, :mentor, 'OPEN', :at)
					""").param("id", invitation).param("job", post).param("product", product)
				.param("customer", customer).param("mentor", mentor).param("at", written).update();
			this.values.put("jobId", this.job.toString());
			this.values.put("boardId", this.job.toString());
			this.values.put("postId", post.toString());
			this.values.put("productId", product.toString());
			this.values.put("crewChatId", this.crewChat.toString());
			this.values.put("messageId", message.toString());
			this.values.put("workItemId", item.toString());
			this.values.put("workItemNoteId", note.toString());
			this.values.put("attachmentId", attachment.toString());
			this.values.put("pageId", page.toString());
			this.values.put("versionNo", "1");
			this.values.put("milestoneId", milestone.toString());
			this.values.put("crewMilestoneId", milestone.toString());
			this.values.put("crewCriterionId", criterion.toString());
			this.values.put("crewRowId", coRow.toString());
			this.values.put("paymentOrderId", order.toString());
			this.values.put("handoverId", handover.toString());
			this.values.put("changeRequestId", changeRequest.toString());
			this.values.put("disputeId", dispute.toString());
			this.values.put("teamIssueId", teamIssue.toString());
			this.values.put("reportId", report.toString());
			this.values.put("applicationId", application.toString());
			this.values.put("accessId", access.toString());
			this.values.put("screenshotId", screenshot.toString());
			this.values.put("fileId", file.toString());
			this.values.put("customerId", customer.toString());
			this.values.put("item", "NON_CODE_DATA");
			this.values.put("kind", "PRODUCT");
			this.values.put("contentId", product.toString());
			this.values.put("username", TeamTestSupport.login(jdbc, mentor));
			List<UUID> objects = new ArrayList<>(List.of(this.job, product, post, invitation, this.crewChat, jobChat,
					message, item, note, attachment, file, page, application, order, coRow, screenshot, dispute,
					changeRequest, teamIssue, report, access, handover, releasedHandover, milestone, released,
					criterion));
			for (@Nullable UUID row : new UUID[] { activeRow, formerRow }) {
				if (row != null) {
					objects.add(row);
				}
			}
			objects.forEach(id -> this.objectIds.add(id.toString()));
			this.objectIds.add(canary("item-title"));
			this.objectIds.add(canary("page-title"));
			for (UUID id : List.of(message, item, note, attachment, file, page, customerPage, handover, changeRequest,
					dispute, teamIssue, report)) {
				this.contentAfterTheWindow.add(id.toString());
			}
			this.writtenAfterTheWindow.addAll(this.contentAfterTheWindow);
			this.writtenAfterTheWindow.add(post.toString());
			this.writtenAfterTheWindow.add(invitation.toString());
		}

		/** The placeholder's value on this Job; one it holds no object for fails the suite. */
		String value(String name) {
			String value = this.values.get(name);
			if (value == null) {
				throw new AssertionError("Job " + this.tag + " seeds no object for {" + name + "}: seed one, or leave the "
						+ "read out of the sweep (NOT_SWEPT) with the reason");
			}
			return value;
		}

		String canary(String kind) {
			return switch (kind) {
				case "crew-split" -> Long.toString(this.coPart);
				case "crew-email", "contact" -> "xr-" + this.tag.toLowerCase() + "-" + kind + "-41c7@example.test";
				case "crew-link" -> "https://www.linkedin.com/in/xr-" + this.tag.toLowerCase() + "-crew-41c7";
				default -> "xr-" + this.tag + "-" + kind + "-41c7";
			};
		}

		/** The Crew member's identity: shown name and LinkedIn link (the email is hidden from every person). */
		Set<String> crewIdentity() {
			return Set.of(canary("crew-name"), canary("crew-link"));
		}

		/** What the Crew writes and reads, all of it written after the former member's window ended. */
		Set<String> crewContent() {
			return Set.of(canary("crew-chat"), canary("note"), canary("attachment"), canary("page"));
		}

		/** The Customer's: the Job Chat and, when the Customer is no one of the three, their contact email. */
		Set<String> customerData() {
			return this.customerIsAPerson ? Set.of(canary("job-chat")) : Set.of(canary("job-chat"), canary("contact"));
		}

		/** The other member's part of the release, as digits and as VND shows it ({@code %,d} with dots, E6). */
		Set<String> coSplit() {
			return amounts(this.coPart);
		}

		/** The Crew's parts of the release, in VND: the other member's and, on the person's Crew, their own. */
		Set<String> crewSplit() {
			Set<String> split = new LinkedHashSet<>(coSplit());
			if (this.ownPart != null) {
				split.addAll(amounts(this.ownPart));
			}
			return split;
		}

		/** The person's own part of the release, as digits; only a Job with the person on its Crew has one. */
		String ownSplit() {
			return Long.toString(Objects.requireNonNull(this.ownPart, "Job " + this.tag + " has no person on its Crew"));
		}

		/**
		 * What another Job's reader may never see of this Job (review iterations 3 and 4, B8, B5): the IDs of the Job,
		 * its Product, its Open post and invitation, its Chats and message, Work Item, note, attachment and Page, the
		 * bid, the payment order, the Crew rows and the screenshot, the Dispute, Change Request, Team Issue, Abuse
		 * Report, External Access item, handovers, Milestones and criterion, and the Work Item's and Page's titles.
		 */
		Set<String> objectIds() {
			return this.objectIds;
		}

		/** The IDs of the Job content written after the former member's window ended. */
		Set<String> contentAfterTheWindow() {
			return this.contentAfterTheWindow;
		}

		/** The IDs of the Job's objects written after the former member's window ended, its Open post's included. */
		Set<String> writtenAfterTheWindow() {
			return this.writtenAfterTheWindow;
		}

		private static Set<String> amounts(long vnd) {
			return Set.of(Long.toString(vnd), String.format(Locale.ROOT, "%,d", vnd).replace(',', '.'));
		}

		/** Every canary of the Job. */
		Set<String> all() {
			Set<String> all = new HashSet<>(crewIdentity());
			all.add(canary("item-title"));
			all.add(canary("page-title"));
			all.add(canary("customer-page"));
			all.add(canary("early-page"));
			all.addAll(crewSplit());
			all.addAll(crewContent());
			all.addAll(customerData());
			all.add(canary("crew-email"));
			all.add(canary("early-chat"));
			return all;
		}

	}

}
