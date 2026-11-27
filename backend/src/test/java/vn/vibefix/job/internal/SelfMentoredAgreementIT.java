package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.CrewPicks;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestPeople;

/**
 * FR-16, FR-17, FR-75, FR-85, AD-47 (exception 3, B-11), AD-41, Story 25.3: in a Self-mentored Job the Customer drafts
 * the Agreement and confirms it in one step, with the Freeze Commitment for a Code Job; the steps of a two-party
 * Agreement find nothing; and a picked applicant and an active member read the Customer's live draft, are told once when
 * the draft they read changes, and read every confirmed version afterwards.
 */
@TestPropertySource(properties = "vibefix.features.self-mentored-jobs=true")
class SelfMentoredAgreementIT extends IntegrationTest {

	private static final String NOTICE = "{\"selfMentoredNoticeVersion\":\"" + J11PublishSelfMentored.NOTICE_VERSION + "\"}";

	private static final UUID MILESTONE = UUID.fromString("00000000-0000-7000-8000-00000000b001");

	private static final UUID CRITERION = UUID.fromString("00000000-0000-7000-8000-00000000b002");

	private static final UUID ACCESS = UUID.fromString("00000000-0000-7000-8000-00000000b003");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private CrewPicks picks;

	@Autowired
	private vn.vibefix.job.AgreementHistory history;

	private UUID lan;

	private UUID job;

	@BeforeEach
	void aSelfMentoredJobInAgreement() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID product = product(this.lan);
		String created = this.mvc.perform(post("/api/v1/jobs").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("""
					{"productId":"%s","title":"Thêm thanh toán online","problem":"Khách chưa trả tiền trước được.",
					 "expected":"Khách trả tiền cọc khi đặt bàn.","appUsers":"Khách đặt bàn trên điện thoại.",
					 "jobTypes":["BUG_FIX"],"skillIds":[],"budget":"FROM_2M_TO_5M","deadline":"2026-12-15","selfMentored":true}
					""".formatted(product))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		this.job = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created, "$.id"));
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/publish").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(NOTICE)).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("AGREEMENT"));
	}

	@Test
	void theCustomerDraftsAndConfirmsTheAgreementAloneInOneStep() throws Exception {
		this.mvc.perform(get("/api/v1/mentor/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.revision").value(0))
			.andExpect(jsonPath("$.quote").doesNotExist());

		// Nothing is confirmed while the draft lacks something, and the answer says what.
		confirm(jobVersion(), 0, 0, true, List.of()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("AGREEMENT_INCOMPLETE"))
			.andExpect(jsonPath("$.problems[0].code").value("SCOPE_MISSING"));
		save(0, "Thêm bước trả tiền cọc khi đặt bàn.").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.problems.length()").value(0));

		// The one step carries the draft revision the screen loaded, N-6 for a Code Job, and the live systems.
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/confirm").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"crewVersion\":0}".formatted(jobVersion()))).andExpect(status().isBadRequest());
		confirm(jobVersion(), 0, 0, true, List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		confirm(jobVersion(), 0, 1, false, List.of()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("FREEZE_COMMITMENT_REQUIRED"));
		// A live system the draft does not list, and one that is no ID at all, are refused as what the request got wrong.
		confirm(jobVersion(), 0, 1, true, List.of(UUID.randomUUID())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"));
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/confirm").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"crewVersion\":0,\"revision\":1,\"freezeCommitment\":true,\"liveAccessIds\":[null]}"
				.formatted(jobVersion()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
		confirm(jobVersion() + 1, 0, 1, true, List.of()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		assertThat(count("SELECT count(*) FROM job.confirmed_agreement WHERE job_id = :id")).isZero();
		assertThat(count("SELECT count(*) FROM job.agreement_version WHERE job_id = :id")).isZero();

		long version = jobVersion();
		confirm(version, 0, 1, true, List.of(ACCESS)).andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.confirmed.versionNo").value(1))
			.andExpect(jsonPath("$.confirmed.freezeCommitted").value(true))
			.andExpect(jsonPath("$.confirmed.liveAccessIds[0]").value(ACCESS.toString()))
			.andExpect(jsonPath("$.confirmed.crew.length()").value(0))
			.andExpect(jsonPath("$.confirmed.content.scope").value("Thêm bước trả tiền cọc khi đặt bàn."));

		// One party, recorded once: the Customer is the confirmer on both sides of the row, and nothing was shared or
		// approved on the way.
		assertThat(this.jdbc.sql("""
				SELECT (customer_account_id = :lan) || ' ' || (mentor_account_id = :lan) || ' ' || (approved_at = confirmed_at)
				    || ' ' || freeze_committed || ' ' || draft_version_no
				FROM job.confirmed_agreement WHERE job_id = :id AND version_no = 1
				""").param("id", this.job).param("lan", this.lan).query(String.class).single()).isEqualTo("true true true true 1");
		assertThat(this.jdbc.sql("SELECT status || ' ' || (author_account_id = :lan) FROM job.agreement_version WHERE job_id = :id")
			.param("id", this.job).param("lan", this.lan).query(String.class).list()).containsExactly("CONFIRMED_ALONE true");
		assertThat(count("SELECT count(*) FROM job.agreement_approval WHERE job_id = :id")).isZero();
		// The history a Dispute's Evidence Package reads says it too, so it never tells the one step as two.
		assertThat(this.history.confirmedVersions(this.job)).singleElement()
			.satisfies(found -> assertThat(found.alone()).as("confirmed alone").isTrue());
		assertThat(this.jdbc.sql("SELECT agreement_version_no || ' ' || version FROM job.job WHERE id = :id").param("id", this.job)
			.query(String.class).single()).isEqualTo("1 " + (version + 1));
		assertThat(this.jdbc.sql("""
				SELECT action FROM audit.audit_entry WHERE job_id = :id AND action LIKE 'job.agreement_%' ORDER BY action
				""").param("id", this.job).query(String.class).list()).as("the one actor is recorded once, for the one step")
			.containsExactly("job.agreement_confirmed");
		assertThat(this.jdbc.sql("""
				SELECT actor_account_id FROM audit.audit_entry WHERE job_id = :id AND action = 'job.agreement_confirmed'
				""").param("id", this.job).query(UUID.class).single()).isEqualTo(this.lan);

		// The one account is told once, the drafting to-do is done, and nobody is asked to review or confirm anything.
		assertThat(notifications("job.agreement_confirmed", this.lan)).isOne();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE job_id = :id AND type = 'job.agreement_draft_ready'"))
			.isZero();
		assertThat(this.jdbc.sql("""
				SELECT todo_key FROM notification.todo
				WHERE job_id = :id AND closed_at IS NULL AND todo_key LIKE 'job.%' ORDER BY todo_key
				""").param("id", this.job).query(String.class).list()).containsExactly("job.grant_external_access");
		// No member has signed, so Milestone 1 does not ask for its funding yet (R7).
		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).isZero();

		// Confirmed once: the step does not run twice, and the draft is closed.
		confirm(jobVersion(), 0, 1, true, List.of(ACCESS)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STALE"));
		save(1, "Khác").andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("AGREEMENT_CONFIRMED"));
		// The Customer's own read of the Agreement shows the confirmed version too.
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmed.versionNo").value(1));
	}

	@Test
	void theStepsOfATwoPartyAgreementFindNothingAndNobodyElseConfirms() throws Exception {
		save(0, "Thêm bước trả tiền cọc khi đặt bàn.").andExpect(status().isOk());

		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/share").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}")).andExpect(status().isNotFound());
		review("comments", "{\"versionNo\":1,\"targetKind\":\"SCOPE\",\"body\":\"Giá này hơi cao\"}").andExpect(status().isNotFound());
		review("changes", "{\"version\":%d,\"versionNo\":1,\"note\":\"Hãy thêm nhắc lịch\"}".formatted(jobVersion()))
			.andExpect(status().isNotFound());
		review("approve", "{\"version\":%d,\"versionNo\":1,\"freezeCommitment\":true,\"liveAccessIds\":[]}".formatted(jobVersion()))
			.andExpect(status().isNotFound());
		assertThat(count("SELECT count(*) FROM job.agreement_version WHERE job_id = :id")).isZero();
		// Before the confirmation the Customer's read of shared versions has nothing to show: the editor is the page.
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());

		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/confirm").with(as(other, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"crewVersion\":0,\"revision\":1,\"freezeCommitment\":true}".formatted(jobVersion())))
			.andExpect(status().isNotFound());

		// After the confirmation they still find nothing, though a version now exists.
		confirm(jobVersion(), 0, 1, true, List.of()).andExpect(status().isOk());
		this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/share").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}")).andExpect(status().isNotFound());
		review("comments", "{\"versionNo\":1,\"targetKind\":\"SCOPE\",\"body\":\"Giá này hơi cao\"}").andExpect(status().isNotFound());
		review("approve", "{\"version\":%d,\"versionNo\":1,\"freezeCommitment\":true,\"liveAccessIds\":[]}".formatted(jobVersion()))
			.andExpect(status().isNotFound());
		assertThat(count("SELECT count(*) FROM job.agreement_comment WHERE job_id = :id")).isZero();
		assertThat(count("SELECT count(*) FROM job.agreement_approval WHERE job_id = :id")).isZero();
	}

	@Test
	void thePickedApplicantAndTheMemberReadTheLiveDraftAndAreToldOnceWhenItChanges() throws Exception {
		UUID vy = member("Lê Thảo Vy");
		UUID khoa = member("Trần Minh Khoa");
		UUID nam = member("Phạm Hoài Nam");
		picked(vy);
		active(khoa);
		active(nam);

		// A draft never saved is no Agreement to read yet, and the commitment shows nothing of it.
		crewAgreement(vy, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.selfMentored").value(true))
			.andExpect(jsonPath("$.versions.length()").value(0))
			.andExpect(jsonPath("$.content").doesNotExist());
		assertThat(this.picks.assignment(this.job, vy).orElseThrow().scope()).isNull();

		save(0, "Thêm bước trả tiền cọc khi đặt bàn.").andExpect(status().isOk());
		for (UUID reader : List.of(vy, khoa)) {
			crewAgreement(reader, "").andExpect(status().isOk())
				.andExpect(jsonPath("$.versions.length()").value(1))
				.andExpect(jsonPath("$.shown.confirmed").value(false))
				.andExpect(jsonPath("$.shown.versionNo").value(1))
				.andExpect(jsonPath("$.content.scope").value("Thêm bước trả tiền cọc khi đặt bàn."))
				.andExpect(jsonPath("$.content.milestones[0].priceVnd").value(800000))
				.andExpect(jsonPath("$.changedSinceRead").value(false));
			crewRead(reader, false, 1).andExpect(status().isNoContent());
		}
		// What the picked applicant signs for is that draft: its scope and its Milestones with their prices (R54).
		CrewPicks.Assignment assignment = this.picks.assignment(this.job, vy).orElseThrow();
		assertThat(assignment.scope()).isEqualTo("Thêm bước trả tiền cọc khi đặt bàn.");
		assertThat(assignment.milestones()).singleElement().satisfies(milestone -> {
			assertThat(milestone.title()).isEqualTo("Trả tiền cọc");
			assertThat(milestone.priceVnd()).isEqualTo(800000L);
		});

		// The Customer edits: whoever read revision 1 is told once, however many saves follow; a member who never opened
		// the Agreement is told nothing.
		save(1, "Thêm bước trả tiền cọc, có nhắc lịch.").andExpect(status().isOk());
		save(2, "Thêm bước trả tiền cọc, có nhắc lịch và hoàn cọc.").andExpect(status().isOk());
		assertThat(notifications("job.agreement_draft_changed", vy)).isOne();
		assertThat(notifications("job.agreement_draft_changed", khoa)).isOne();
		assertThat(notifications("job.agreement_draft_changed", nam)).isZero();
		crewAgreement(vy, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.shown.versionNo").value(3))
			.andExpect(jsonPath("$.content.scope").value("Thêm bước trả tiền cọc, có nhắc lịch và hoàn cọc."))
			.andExpect(jsonPath("$.changedSinceRead").value(true));
		// A revision the draft left behind is no version: the reader finds nothing under its number.
		crewAgreement(vy, "?version=1&confirmed=false").andExpect(status().isNotFound());
		crewRead(vy, false, 1).andExpect(status().isNotFound());

		// Khoa opens the draft as it is now; a later edit tells him again, and Vy, still at revision 1, nothing more.
		crewRead(khoa, false, 3).andExpect(status().isNoContent());
		save(3, "Thêm bước trả tiền cọc, có nhắc lịch, hoàn cọc và biên nhận.").andExpect(status().isOk());
		assertThat(notifications("job.agreement_draft_changed", khoa)).isEqualTo(2);
		assertThat(notifications("job.agreement_draft_changed", vy)).isOne();
		crewRead(khoa, false, 4).andExpect(status().isNoContent());

		// The confirmation: Khoa read the revision that was confirmed, so he has read version 1 and hears nothing more of
		// the draft; Vy, behind it, is told a newer version is readable. Both hear that the Agreement is confirmed only if
		// active, and a member has signed, so Milestone 1 asks for its funding.
		confirm(jobVersion(), crewVersion(), 4, true, List.of()).andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmed.crew.length()").value(2));
		assertThat(notifications("job.agreement_draft_changed", khoa)).isEqualTo(2);
		assertThat(notifications("job.agreement_draft_changed", vy)).isEqualTo(2);
		assertThat(readMark(khoa)).isEqualTo("true:1");
		assertThat(readMark(vy)).isEqualTo("false:1");
		assertThat(notifications("job.agreement_confirmed", khoa)).isOne();
		assertThat(notifications("job.agreement_confirmed", vy)).isZero();
		assertThat(notifications("job.agreement_confirmed", this.lan)).isOne();
		assertThat(notifications("job.milestone_awaiting_funding", this.lan)).isOne();

		for (UUID reader : List.of(vy, khoa)) {
			crewAgreement(reader, "").andExpect(status().isOk())
				.andExpect(jsonPath("$.versions.length()").value(1))
				.andExpect(jsonPath("$.shown.confirmed").value(true))
				.andExpect(jsonPath("$.shown.versionNo").value(1))
				.andExpect(jsonPath("$.content.scope").value("Thêm bước trả tiền cọc, có nhắc lịch, hoàn cọc và biên nhận."));
		}
		crewAgreement(vy, "").andExpect(jsonPath("$.changedSinceRead").value(true));
		crewAgreement(khoa, "").andExpect(jsonPath("$.changedSinceRead").value(false));
		// The draft's revision is no version any more.
		crewAgreement(khoa, "?version=4&confirmed=false").andExpect(status().isNotFound());
		// The Customer and a stranger never read the Crew's view.
		crewAgreement(this.lan, "").andExpect(status().isNotFound());
		crewAgreement(member("Đỗ Gia Huy"), "").andExpect(status().isNotFound());
	}

	private ResultActions confirm(long version, long crewVersion, int revision, boolean freeze, List<UUID> live)
			throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/jobs/" + this.job + "/agreement/confirm").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":%d,\"crewVersion\":%d,\"revision\":%d,\"freezeCommitment\":%s,\"liveAccessIds\":[%s]}"
				.formatted(version, crewVersion, revision, freeze,
						String.join(",", live.stream().map(id -> "\"" + id + "\"").toList()))));
	}

	private ResultActions review(String step, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/agreement/" + step).with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions save(int revision, String scope) throws Exception {
		return this.mvc.perform(put("/api/v1/mentor/jobs/" + this.job + "/agreement").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"revision":%d,"scope":"%s","priceVnd":800000,"skillIds":[],
					 "milestones":[{"id":"%s","title":"Trả tiền cọc","priceVnd":800000,"dueOn":"2026-12-10","revisionRounds":2,
					   "criteria":[{"id":"%s","statement":"Khách trả được tiền cọc","checkSteps":"1. Mở. 2. Đặt. 3. Trả."}]}],
					 "externalAccess":[{"id":"%s","system":"Supabase","purpose":"Đọc bảng đặt bàn","environment":"NON_PRODUCTION",
					   "accessLevel":"READ"}]}
					""".formatted(revision, scope, MILESTONE, CRITERION, ACCESS)));
	}

	private ResultActions crewAgreement(UUID account, String query) throws Exception {
		return this.mvc.perform(get("/api/v1/crew/jobs/" + this.job + "/agreement" + query).with(as(account, Role.STUDENT)));
	}

	private ResultActions crewRead(UUID account, boolean confirmed, int versionNo) throws Exception {
		return this.mvc.perform(post("/api/v1/crew/jobs/" + this.job + "/agreement/read").with(as(account, Role.STUDENT))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"confirmed\":%s,\"versionNo\":%d}".formatted(confirmed, versionNo)));
	}

	/** The reader's row as "confirmed:version". */
	private String readMark(UUID account) {
		return this.jdbc.sql("""
				SELECT confirmed || ':' || version_no FROM job.agreement_read WHERE job_id = :job AND account_id = :account
				""").param("job", this.job).param("account", account).query(String.class).single();
	}

	/** A pick not yet signed: an {@code ASSIGNED} row, as {@code job.addCrew} leaves it. */
	private void picked(UUID member) {
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, crew_application_id, created_at)
				VALUES (:id, :job, :member, 'ASSIGNED', :application, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("member", member)
			.param("application", UUID.randomUUID()).param("now", Timestamp.from(this.clock.instant())).update();
	}

	/** A member who signed: an {@code ACTIVE} row, as {@code job.activateCrew} leaves it. */
	private void active(UUID member) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, 'ACTIVE', :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("member", member).param("now", now).update();
		this.jdbc.sql("UPDATE job.job SET crew_version = crew_version + 1 WHERE id = :id").param("id", this.job).update();
	}

	private UUID member(String name) {
		return TestPeople.student(this.jdbc, name, true, List.of(TestPeople.REACT), "React", LocalDate.parse("2026-09-30"));
	}

	private long jobVersion() {
		return this.jdbc.sql("SELECT version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long crewVersion() {
		return this.jdbc.sql("SELECT crew_version FROM job.job WHERE id = :id").param("id", this.job).query(Long.class).single();
	}

	private long notifications(String type, UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :account")
			.param("type", type).param("account", account).query(Long.class).single();
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).param("id", this.job).query(Long.class).single();
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github,
				    'lan/so-dat-ban', 'User', 'main', '1.0', '1.0', '1.0', :now, :now)
				""")
			.param("id", id)
			.param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.param("now", now)
			.update();
		return id;
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
