package vn.vibefix.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;

import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.AccountAdministration;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.internal.CustomerTokens;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.ErasureOutcome;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/** FR-13, AD-19, AD-33, AD-34: Applications to Open Job Posts, their refusals, and what ends them. */
class ApplicationsIT extends IntegrationTest {

	private static final String TOKEN = "gho_customer_token_never_printed";

	private static final HttpServer GITHUB = start();

	/** What the stub answers for {@code /repos/{owner}/{name}/contributors}: a status and a body. */
	private static final Map<String, String> CONTRIBUTORS = new ConcurrentHashMap<>();

	/** Runs on the stub's thread before it answers a contributors read, to look at the database meanwhile. */
	private static final AtomicReference<Runnable> BEFORE_CONTRIBUTORS = new AtomicReference<>();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private PlatformTransactionManager transactions;

	@Autowired
	private AccountAdministration administration;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private MatchingPersonalData matching;

	private UUID lan;

	private UUID job;

	@DynamicPropertySource
	static void stubGitHub(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
	}

	@BeforeEach
	void aCustomerWithAnOpenJobPost() {
		CONTRIBUTORS.clear();
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "200 [{\"id\":987654321,\"login\":\"lan\"},{\"login\":\"anon\"}]");
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		CustomerTokens.keep(this.context, this.lan, TOKEN);
		this.job = openJob(product(this.lan), "Sửa lỗi mất đơn hàng");
	}

	@AfterEach
	void forget() {
		CONTRIBUTORS.clear();
	}

	@Test
	void aMentorWithAFreeStudentAppliesAndTheCustomerIsToldOnce() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		UUID ha = mentorWithFreeStudent("Phạm Thu Hà");
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/invitations").with(as(this.lan, Role.CUSTOMER)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"mentorId\":\"" + hung + "\"}")).andExpect(status().isCreated());

		apply(hung, this.job, true).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.priceVnd").value(800000))
			.andExpect(jsonPath("$.jobTitle").value("Sửa lỗi mất đơn hàng"));
		assertThat(todoText()).isEqualTo("1");
		this.clock.advance(Duration.ofMinutes(1));
		apply(ha, this.job, true).andExpect(status().isCreated());

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.application_received' AND account_id = :lan
				""").param("lan", this.lan).query(Long.class).single()).as("only the first one notifies").isOne();
		assertThat(todoText()).isEqualTo("2");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[1].mentor.displayName").value("Đỗ Mạnh Hùng"))
			.andExpect(jsonPath("$[1].mentor.teamSize").doesNotExist())
			.andExpect(jsonPath("$[1].approach").value("Tái hiện lỗi rồi sửa và thêm kiểm thử."))
			.andExpect(jsonPath("$[1].invited").value(true))
			.andExpect(jsonPath("$[0].invited").value(false));
		assertThat(this.jdbc.sql("SELECT status FROM matching.invitation WHERE job_id = :job AND mentor_account_id = :hung")
			.param("job", this.job).param("hung", hung).query(String.class).single())
			.as("the invited Mentor's Application answers the invitation").isEqualTo("APPLIED");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'matching.answer_invitation_to_apply' AND closed_at IS NULL
				""").query(Long.class).single()).isZero();
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(other, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/mentor/applications").with(as(hung, Role.MENTOR)))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].jobId").value(this.job.toString()));
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'matching.application_submitted'")
			.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void eachRuleOfFr13RefusesWithItsReason() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		apply(hung, this.job, false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PUBLIC_CODE_NOT_ACKNOWLEDGED"));
		apply(hung, this.job, true).andExpect(status().isCreated());
		apply(hung, this.job, true).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ALREADY_APPLIED"));

		// Story 19.7: a bid needs a profile with a display name, or the Customer could not tell who applied.
		UUID nameless = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		apply(nameless, this.job, true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("PROFILE_REQUIRED"));
		UUID unnamed = mentorWithFreeStudent("Chưa đặt tên");
		this.jdbc.sql("UPDATE identity.profile SET display_name = NULL WHERE account_id = :id").param("id", unnamed).update();
		apply(unnamed, this.job, true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("PROFILE_REQUIRED"));

		// Story 21.2: no Team and no free Student is asked for.
		UUID lonely = TeamTestSupport.mentor(this.jdbc, "Chưa có nhóm",
				List.of(TeamTestSupport.REACT), "React");
		apply(lonely, this.job, true).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUBMITTED"));

		UUID hidden = openJob(product(this.lan), "Tin bị ẩn");
		this.jdbc.sql("UPDATE job.job SET hidden_at = now(), hidden_reason = 'Vi phạm' WHERE id = :id").param("id", hidden).update();
		apply(mentorWithFreeStudent("A"), hidden, true).andExpect(jsonPath("$.reason").value("JOB_POST_HIDDEN"));
		UUID draft = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, created_at, updated_at)
				VALUES (:id, :product, :owner, 'DRAFT', 'Nháp', ARRAY['BUG_FIX'], now(), now())
				""").param("id", draft).param("product", product(this.lan)).param("owner", this.lan).update();
		apply(mentorWithFreeStudent("B"), draft, true).andExpect(jsonPath("$.reason").value("JOB_POST_NOT_OPEN"));

		UUID contributor = mentorWithFreeStudent("Từng đóng góp");
		this.jdbc.sql("UPDATE identity.account SET github_user_id = 987654321 WHERE id = :id").param("id", contributor).update();
		apply(contributor, this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("APPLICANT_IS_CONTRIBUTOR"));
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "502 {}");
		apply(mentorWithFreeStudent("C"), this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONTRIBUTORS_UNKNOWN"));
		assertThat(relinkGitHub()).as("GitHub not answering is not the Customer's to fix").isZero();
		// Author decision 2026-10-21: a refused token still fails closed and asks the Customer to re-link GitHub.
		CONTRIBUTORS.put("/repos/lan/so-dat-ban/contributors", "401 {}");
		apply(mentorWithFreeStudent("D"), this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONTRIBUTORS_UNKNOWN"));
		assertThat(relinkGitHub()).isOne();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'identity.relink_github' AND account_id = :lan
				""").param("lan", this.lan).query(Long.class).single()).isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application").query(Long.class).single())
			.as("a refusal writes nothing").isEqualTo(2);
	}

	@Test
	void aCustomerBiddingOnTheirOwnJobIsRefusedBeforeAnyOtherRule() throws Exception {
		// AD-43: the shim gives this account the Mentor role, and it is also the Customer of an Open Job of its own.
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		UUID own = openJob(product(hung), hung, "Việc của chính tôi");
		this.jdbc.sql("UPDATE identity.profile SET display_name = NULL WHERE account_id = :id").param("id", hung).update();

		apply(hung, own, false).andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
		this.jdbc.sql("UPDATE identity.profile SET display_name = 'Đỗ Mạnh Hùng' WHERE account_id = :id").param("id", hung)
			.update();
		apply(hung, own, true).andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));

		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application WHERE job_id = :job").param("job", own)
			.query(Long.class).single()).as("a refusal writes nothing").isZero();
		apply(hung, this.job, true).andExpect(status().isCreated());
	}

	private long relinkGitHub() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'identity.relink_github' AND account_id = :lan
				    AND closed_at IS NULL
				""").param("lan", this.lan).query(Long.class).single();
	}

	@Test
	void aSharedPersonSignalRefusesWithoutSayingWhyAndAlertsEveryAdministrator() throws Exception {
		UUID admin = administrator();
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		String hmac = "ab".repeat(32);
		for (UUID account : List.of(hung, this.lan)) {
			this.jdbc.sql("""
					INSERT INTO identity.person_signal (id, account_id, kind, source, hmac, key_version, recorded_at)
					VALUES (:id, :account, 'EMAIL', 'GITHUB_EMAIL', :hmac, 1, now())
					""").param("id", UUID.randomUUID()).param("account", account).param("hmac", hmac).update();
		}

		String body = apply(hung, this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("CONFLICT_OF_INTEREST"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContainIgnoringCase("email").doesNotContainIgnoringCase("linkedin");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification
				WHERE type = 'matching.conflict_of_interest_refused' AND account_id = :admin
				""").param("admin", admin).query(Long.class).single()).as("committed despite the refusal").isOne();
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application").query(Long.class).single()).isZero();
	}

	@Test
	void twentyApplicationsADayAndNoMore() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		for (int i = 0; i < Applications.DAILY_LIMIT; i++) {
			UUID other = openJob(product(this.lan), "Tin " + i);
			this.jdbc.sql("""
					INSERT INTO matching.application (id, job_id, product_id, customer_account_id, mentor_account_id, status,
					    price_vnd, timeline_days, approach, public_code_notice_version, submitted_at)
					SELECT :id, j.id, j.product_id, j.customer_account_id, :mentor, 'SUBMITTED', 1000, 1, 'x', '1.0', :now
					FROM job.job j WHERE j.id = :job
					""").param("id", UUID.randomUUID()).param("mentor", hung).param("job", other)
				.param("now", Timestamp.from(this.clock.instant())).update();
		}

		apply(hung, this.job, true).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	/** FR-13, SG-52: the Mentor Job limit, read live under the bidder's lock, refuses a bid with its number. */
	@Test
	void aBidderAtTheMentorJobLimitIsRefusedWithTheLimit() throws Exception {
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		this.jdbc.sql("UPDATE settings.rule_value SET value = 1 WHERE rule_key = 'MENTOR_ACTIVE_JOB_LIMIT'").update();
		UUID running = openJob(product(this.lan), "Đang chọn");
		this.jdbc.sql("""
				UPDATE job.job SET state = 'SELECTING', mentor_account_id = :m, selected_application_id = :a,
				    selected_at = now(), opened_at = NULL, lapses_at = NULL WHERE id = :id
				""").param("m", hung).param("a", UUID.randomUUID()).param("id", running).update();

		apply(hung, this.job, true).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("MENTOR_JOB_LIMIT"))
			.andExpect(jsonPath("$.limit").value(1));
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application").query(Long.class).single()).isZero();
	}

	@Test
	void theMentorWithdrawsAndJ2ClosesTheRest() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		UUID ha = mentorWithFreeStudent("Phạm Thu Hà");
		UUID mine = applicationId(apply(hung, this.job, true));
		apply(ha, this.job, true);

		this.mvc.perform(post("/api/v1/mentor/applications/" + mine + "/withdraw").with(as(ha, Role.MENTOR)).with(csrf()))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/mentor/applications/" + mine + "/withdraw").with(as(hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("WITHDRAWN"));
		// Story 21.2: the Customer is told under the bidder's name, and the proof is due 90 days after the withdrawal.
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'mentor' FROM notification.notification
				WHERE type = 'matching.application_withdrawn' AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(this.jdbc.sql("SELECT proof_due_at = ended_at + INTERVAL '90 days' FROM matching.application WHERE id = :id")
			.param("id", mine).query(Boolean.class).single()).isTrue();
		this.mvc.perform(post("/api/v1/mentor/applications/" + mine + "/withdraw").with(as(hung, Role.MENTOR)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NOT_WITHDRAWABLE"));
		assertThat(todoText()).isEqualTo("1");
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.length()").value(1));

		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/withdraw").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE job_id = :job AND status = 'CLOSED' AND end_reason = 'JOB_WITHDRAWN'
				""").param("job", this.job).query(Long.class).single() == 1);
		assertThat(todoText()).as("the to-do closes with the Job Post").isNull();
	}

	@Test
	void aLockedMentorsApplicationsAreWithdrawnOnce() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		apply(hung, this.job, true).andExpect(status().isCreated());
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), hung, this.clock.instant());

		for (int i = 0; i < 2; i++) {
			new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));
		}

		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM matching.application WHERE status = 'WITHDRAWN' AND end_reason = 'MENTOR_UNAVAILABLE'
				""").query(Long.class).single() == 1);
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.application_auto_withdrawn'
				""").query(Long.class).single() == 1);
		assertThat(this.jdbc.sql("""
				SELECT params ->> 'mentor' FROM notification.notification WHERE type = 'matching.application_auto_withdrawn'
				""").query(String.class).single()).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(todoText()).isNull();
	}

	/**
	 * Story 21.2 (FR-13, AD-20, AD-21): a bid with a PDF and a link. CUS-8 lists both items, the Customer and the bidder
	 * download the file as an attachment with {@code nosniff} and {@code Content-Security-Policy: sandbox}, and nobody
	 * else finds it; a waiting upload is the uploader's alone.
	 */
	@Test
	void aBidCarriesAFileAndALinkThatOnlyTheBidderAndTheCustomerRead() throws Exception {
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		String pdf = fileId(uploadProof(hung, this.job, "bao-cao.pdf", TestFiles.pdf()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.fileName").value("bao-cao.pdf")));
		String spare = fileId(uploadProof(hung, this.job, "du-phong.pdf", TestFiles.pdf()));
		this.mvc.perform(get("/api/v1/mentor/job-posts/" + this.job + "/proof-files").with(as(hung, Role.MENTOR)))
			.andExpect(jsonPath("$.length()").value(2));
		this.mvc.perform(delete("/api/v1/mentor/job-posts/" + this.job + "/proof-files/" + spare).with(as(hung, Role.MENTOR))
			.with(csrf())).andExpect(status().isNoContent());

		bid(hung, this.job, proof(file(pdf, "Báo cáo kiểm thử tôi được phép chia sẻ"),
				link("https://Docs.Example.vn/danh-gia", "Bài đánh giá ứng dụng của tôi"))).andExpect(status().isCreated())
			.andExpect(jsonPath("$.proof.length()").value(2));

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/applications").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$[0].proof[0].position").value(1))
			.andExpect(jsonPath("$[0].proof[0].kind").value("FILE"))
			.andExpect(jsonPath("$[0].proof[0].caption").value("Báo cáo kiểm thử tôi được phép chia sẻ"))
			.andExpect(jsonPath("$[0].proof[0].fileName").value("bao-cao.pdf"))
			.andExpect(jsonPath("$[0].proof[0].fileUrl").value("/api/v1/files/" + pdf))
			.andExpect(jsonPath("$[0].proof[1].kind").value("LINK"))
			.andExpect(jsonPath("$[0].proof[1].url").value("https://Docs.Example.vn/danh-gia"))
			.andExpect(jsonPath("$[0].proof[1].domain").value("docs.example.vn"));
		this.mvc.perform(get("/api/v1/mentor/applications").with(as(hung, Role.MENTOR)))
			.andExpect(jsonPath("$[0].proof.length()").value(2));
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'matching.application_received' AND account_id = :lan
				""").param("lan", this.lan).query(Long.class).single()).as("the Customer is told of the first bid").isOne();

		for (RequestPostProcessor reader : List.of(as(this.lan, Role.CUSTOMER), as(hung, Role.MENTOR))) {
			this.mvc.perform(get("/api/v1/files/" + pdf).with(reader))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Security-Policy", "sandbox"))
				.andExpect(header().string("Content-Disposition", Matchers.startsWith("attachment")));
		}
		UUID stranger = TeamTestSupport.mentor(this.jdbc, "Người lạ", List.of(TeamTestSupport.REACT), "React");
		UUID otherCustomer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		for (RequestPostProcessor other : List.of(as(stranger, Role.MENTOR), as(otherCustomer, Role.CUSTOMER),
				as(administrator(), Role.ADMINISTRATOR))) {
			this.mvc.perform(get("/api/v1/files/" + pdf).with(other)).andExpect(status().isNotFound());
		}
		String waiting = fileId(uploadProof(stranger, this.job, "cho.pdf", TestFiles.pdf()));
		this.mvc.perform(get("/api/v1/files/" + waiting).with(as(this.lan, Role.CUSTOMER))).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/files/" + waiting).with(as(stranger, Role.MENTOR))).andExpect(status().isOk());
		this.mvc.perform(delete("/api/v1/mentor/job-posts/" + this.job + "/proof-files/" + waiting).with(as(hung, Role.MENTOR))
			.with(csrf())).andExpect(status().isNotFound());
		uploadProof(hung, this.job, "muon.pdf", TestFiles.pdf()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("ALREADY_APPLIED"));
		uploadProof(this.lan, this.job, "cua-toi.pdf", TestFiles.pdf()).andExpect(status().is(422))
			.andExpect(jsonPath("$.reason").value("ROLE_TAKEN"));
	}

	/** SG-58, AD-21: six items, a link that is not {@code https} with a host, or a file not the bidder's are refused. */
	@Test
	void aSixthProofItemABadLinkOrAnotherFileIsRefusedAndWritesNothing() throws Exception {
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		String six = proof(link("https://a.vn/1", "Một"), link("https://a.vn/2", "Hai"), link("https://a.vn/3", "Ba"),
				link("https://a.vn/4", "Bốn"), link("https://a.vn/5", "Năm"), link("https://a.vn/6", "Sáu"));
		bid(hung, this.job, six).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION"))
			.andExpect(jsonPath("$.reason").value("PROOF_LIMIT"));
		for (String bad : List.of("http://a.vn/x", "https://", "https://user:pass@a.vn/x", "javascript:alert(1)",
				"https://a.vn/có khoảng trắng")) {
			bid(hung, this.job, proof(link(bad, "Liên kết"))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("LINK_INVALID"));
		}
		UUID other = TeamTestSupport.mentor(this.jdbc, "Người khác", List.of(TeamTestSupport.REACT), "React");
		String theirs = fileId(uploadProof(other, this.job, "cua-ho.pdf", TestFiles.pdf()));
		bid(hung, this.job, proof(file(theirs, "Không phải của tôi"))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PROOF_FILE_UNKNOWN"));
		String mine = fileId(uploadProof(hung, this.job, "cua-toi.pdf", TestFiles.pdf()));
		bid(hung, this.job, proof(file(mine, "Một"), file(mine, "Hai"))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PROOF_FILE_UNKNOWN"));
		for (int i = 0; i < BidProofs.MAX - 1; i++) {
			uploadProof(hung, this.job, "tep-" + i + ".pdf", TestFiles.pdf()).andExpect(status().isCreated());
		}
		uploadProof(hung, this.job, "tep-thu-sau.pdf", TestFiles.pdf()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("PROOF_LIMIT"));
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application").query(Long.class).single())
			.as("a refusal writes nothing").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.bid_proof WHERE application_id IS NOT NULL")
			.query(Long.class).single()).isZero();
	}

	/**
	 * A-03 (AD-4, AD-6): the Source Repository's contributors are read before the bid's transaction, so GitHub is never
	 * asked under the bidder's advisory lock; the read refuses to run inside a transaction at all.
	 */
	@Test
	void theContributorReadRunsBeforeTheBidsTransactionAndUnderNoLock() throws Exception {
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		long key = hung.getMostSignificantBits() ^ hung.getLeastSignificantBits();
		AtomicReference<Long> heldDuringRead = new AtomicReference<>();
		BEFORE_CONTRIBUTORS.set(() -> heldDuringRead.set(this.jdbc.sql("""
				SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objsubid = 1 AND granted
				  AND classid::bigint = :high AND objid::bigint = :low
				""").param("high", key >>> 32).param("low", key & 0xFFFFFFFFL).query(Long.class).single()));
		try {
			apply(hung, this.job, true).andExpect(status().isCreated());
		}
		finally {
			BEFORE_CONTRIBUTORS.set(null);
		}
		assertThat(heldDuringRead.get()).as("GitHub was asked, under no advisory lock of the bidder").isZero();

		SourceContributors contributors = this.context.getBean(SourceContributors.class);
		assertThatThrownBy(() -> new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> contributors.read(this.job, hung))).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * X3 (NFR-10, AD-24): a bid that ended unselected keeps its proof for 90 days, then the purge deletes the rows and
	 * the files and audits it; a live bid's proof stays.
	 */
	@Test
	void unselectedProofIsPurgedNinetyDaysAfterTheBidEnded() throws Exception {
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		UUID ha = TeamTestSupport.mentor(this.jdbc, "Phạm Thu Hà", List.of(TeamTestSupport.REACT), "React");
		String withdrawnFile = fileId(uploadProof(hung, this.job, "rut.pdf", TestFiles.pdf()));
		UUID withdrawn = applicationId(bid(hung, this.job, proof(file(withdrawnFile, "Tệp"), link("https://a.vn/x", "Link"))));
		String liveFile = fileId(uploadProof(ha, this.job, "con.pdf", TestFiles.pdf()));
		bid(ha, this.job, proof(file(liveFile, "Tệp"))).andExpect(status().isCreated());
		this.mvc.perform(post("/api/v1/mentor/applications/" + withdrawn + "/withdraw").with(as(hung, Role.MENTOR))
			.with(csrf())).andExpect(status().isOk());
		BidProofPurge purge = this.context.getBean(BidProofPurge.class);
		StoredFiles files = this.context.getBean(StoredFiles.class);

		this.clock.advance(Duration.ofDays(89));
		assertThat(purge.purge()).as("not due yet").isZero();
		this.clock.advance(Duration.ofDays(1));
		assertThat(purge.purge()).isEqualTo(2);

		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.bid_proof WHERE application_id = :id").param("id", withdrawn)
			.query(Long.class).single()).isZero();
		assertThat(files.find(UUID.fromString(withdrawnFile))).as("the file goes with its row").isEmpty();
		assertThat(files.find(UUID.fromString(liveFile))).as("a live bid keeps its proof").isPresent();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry e JOIN audit.audit_entry_object o ON o.entry_id = e.id
				WHERE e.action = 'matching.bid_proof_purged' AND o.object_id = :id
				""").param("id", withdrawn).query(Long.class).single()).isOne();
		assertThat(purge.purge()).as("purging again deletes nothing").isZero();
	}

	/**
	 * AD-24: the proof goes with the Job's data at the end of its retention (the bid stays), with the Product, and with
	 * the bidder's erasure; the bidder's export lists it, and nobody else's does.
	 */
	@Test
	void theProofGoesWithTheJobsDataTheProductAndTheBiddersErasure() throws Exception {
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		String pdf = fileId(uploadProof(hung, this.job, "bao-cao.pdf", TestFiles.pdf()));
		bid(hung, this.job, proof(file(pdf, "Báo cáo riêng 4c2e"), link("https://a.vn/x", "Liên kết")))
			.andExpect(status().isCreated());
		StoredFiles files = this.context.getBean(StoredFiles.class);

		assertThat(this.matching.export(hung).orElseThrow().toString()).contains("Báo cáo riêng 4c2e", "https://a.vn/x");
		assertThat(this.matching.export(this.lan).map(Object::toString).orElse("")).doesNotContain("Báo cáo riêng 4c2e");
		UUID product = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id").param("id", this.job)
			.query(UUID.class).single();
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.matching.retireJobData(product));
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.bid_proof").query(Long.class).single()).isZero();
		assertThat(files.find(UUID.fromString(pdf))).isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application WHERE mentor_account_id = :id").param("id", hung)
			.query(Long.class).single()).as("the bid stays").isOne();

		UUID other = openJob(product(this.lan), "Tin khác");
		String second = fileId(uploadProof(hung, other, "khac.pdf", TestFiles.pdf()));
		bid(hung, other, proof(file(second, "Tệp"))).andExpect(status().isCreated());
		UUID otherProduct = this.jdbc.sql("SELECT product_id FROM job.job WHERE id = :id").param("id", other)
			.query(UUID.class).single();
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.matching.deleteProduct(otherProduct));
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.bid_proof").query(Long.class).single()).isZero();
		assertThat(files.find(UUID.fromString(second))).isEmpty();

		UUID third = openJob(product(this.lan), "Tin thứ ba");
		String waiting = fileId(uploadProof(hung, third, "cho.pdf", TestFiles.pdf()));
		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.matching.eraseAccount(hung));
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.bid_proof").query(Long.class).single()).isZero();
		assertThat(files.find(UUID.fromString(waiting))).isEmpty();
	}

	private ResultActions uploadProof(UUID bidder, UUID jobId, String name, byte[] content) throws Exception {
		return this.mvc.perform(multipart("/api/v1/mentor/job-posts/" + jobId + "/proof-files")
			.file(new MockMultipartFile("file", name, "application/pdf", content))
			.with(as(bidder, Role.MENTOR))
			.with(csrf()));
	}

	private ResultActions bid(UUID mentor, UUID jobId, String proof) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/job-posts/" + jobId + "/applications").with(as(mentor, Role.MENTOR))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"priceVnd":800000,"timelineDays":7,"approach":"Tái hiện lỗi rồi sửa và thêm kiểm thử.",
					 "publicCodeAcknowledged":true,"proof":%s}
					""".formatted(proof)));
	}

	private static String proof(String... items) {
		return "[" + String.join(",", items) + "]";
	}

	private static String file(String fileId, String caption) {
		return "{\"kind\":\"FILE\",\"fileId\":\"" + fileId + "\",\"caption\":\"" + caption + "\"}";
	}

	private static String link(String url, String caption) {
		return "{\"kind\":\"LINK\",\"url\":\"" + url + "\",\"caption\":\"" + caption + "\"}";
	}

	private static String fileId(ResultActions uploaded) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(uploaded.andReturn().getResponse().getContentAsString(), "$.fileId");
	}

	private ResultActions apply(UUID mentor, UUID jobId, boolean acknowledged) throws Exception {
		return apply(as(mentor, Role.MENTOR), jobId, acknowledged);
	}

	private ResultActions apply(RequestPostProcessor bidder, UUID jobId, boolean acknowledged) throws Exception {
		return this.mvc.perform(post("/api/v1/mentor/job-posts/" + jobId + "/applications").with(bidder)
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"priceVnd":800000,"timelineDays":7,"approach":"Tái hiện lỗi rồi sửa và thêm kiểm thử.",
					 "publicCodeAcknowledged":%s}
					""".formatted(acknowledged)));
	}

	private static UUID applicationId(ResultActions created) throws Exception {
		return UUID.fromString(com.jayway.jsonpath.JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	/** The open to-do's count, or {@code null} without an open one. */
	private String todoText() {
		return this.jdbc.sql("""
				SELECT params ->> 'n' FROM notification.todo
				WHERE todo_key = 'matching.choose_assignee' AND subject_id = :job AND closed_at IS NULL
				""").param("job", this.job).query(String.class).optional().orElse(null);
	}

	private UUID mentorWithFreeStudent(String name) {
		return withFreeStudent(TeamTestSupport.mentor(this.jdbc, name, List.of(TeamTestSupport.REACT), "React"), name);
	}

	/** Gives the account a Team with one free Student, as FR-13 needs to take a Job. */
	private UUID withFreeStudent(UUID mentor, String name) {
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", mentor)
			.update();
		UUID student = TeamTestSupport.student(this.jdbc, "Sinh viên của " + name, true, List.of(TeamTestSupport.REACT),
				"React", LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())
				""").param("id", UUID.randomUUID()).param("team", team).param("student", student).update();
		return mentor;
	}

	/** Story 20.3: a Google account with no role bids; locking it withdraws the bid, as for any other bidder. */
	@Test
	void aUserWithNoRoleBidsAndALockWithdrawsTheBid() throws Exception {
		UUID minh = withFreeStudent(TeamTestSupport.googleMentor(this.jdbc, "Ngô Văn Minh", List.of(TeamTestSupport.REACT),
				"React"), "Ngô Văn Minh");
		apply(IdentityTestSupport.signedIn(this.sessions, minh, null, this.clock.instant()), this.job, true)
			.andExpect(status().isCreated());
		IdentityTestSupport.lock(this.jdbc, minh, "Vi phạm");
		AccountLocked locked = new AccountLocked(UUID.randomUUID(), minh, this.clock.instant());

		new TransactionTemplate(this.transactions).executeWithoutResult(status -> this.events.publishEvent(locked));

		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql("""
				SELECT count(*) FROM matching.application
				WHERE mentor_account_id = :minh AND status = 'WITHDRAWN' AND end_reason = 'MENTOR_UNAVAILABLE'
				""").param("minh", minh).query(Long.class).single() == 1);

		// Story 20.4: an unlock brings no bid back.
		UUID admin = administrator();
		new TransactionTemplate(this.transactions)
			.executeWithoutResult(status -> this.administration.unlock(admin, minh, "Đã làm rõ"));
		awaitListeners();
		assertThat(this.jdbc.sql("SELECT status FROM matching.application WHERE mentor_account_id = :minh").param("minh", minh)
			.query(String.class).single()).isEqualTo("WITHDRAWN");
	}

	/**
	 * Story 20.4 (AD-24): matching blocks no erasure. Erasing an unlocked Mentor with an open bid first withdraws the
	 * bid through the lock's path, so the Customer is told under the Mentor's name, and then deletes it.
	 */
	@Test
	void anErasureWithdrawsTheOpenBidAndTellsTheCustomerBeforeDeletingIt() throws Exception {
		UUID hung = mentorWithFreeStudent("Đỗ Mạnh Hùng");
		apply(hung, this.job, true).andExpect(status().isCreated());
		assertThat(this.matching.erasureBlockers(hung)).isEmpty();

		ErasureOutcome outcome = new TransactionTemplate(this.transactions).execute(status -> this.personalData.erase(hung));
		assertThat(outcome).isInstanceOf(ErasureOutcome.Erased.class);

		assertThat(this.jdbc.sql("""
				SELECT params ->> 'mentor' FROM notification.notification
				WHERE type = 'matching.application_auto_withdrawn' AND account_id = :lan
				""").param("lan", this.lan).query(String.class).single()).isEqualTo("Đỗ Mạnh Hùng");
		assertThat(this.jdbc.sql("SELECT count(*) FROM matching.application WHERE mentor_account_id = :hung")
			.param("hung", hung).query(Long.class).single()).as("then the data is deleted").isZero();
		assertThat(todoText()).as("no Application is left to choose").isNull();
		awaitListeners();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(10)).until(() -> this.jdbc.sql(
				"SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
	}

	private UUID administrator() {
		UUID admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		this.jdbc.sql("""
				INSERT INTO identity.administrator_credential (account_id, email, display_name, password_hash,
				    totp_key_version, totp_nonce, totp_ciphertext, created_at)
				VALUES (:id, :email, 'Quản trị', '$argon2id$x', 1, '\\x00'::bytea, '\\x00'::bytea, now())
				""").param("id", admin).param("email", "qt-" + admin.toString().substring(0, 8) + "@vibefix.vn").update();
		return admin;
	}

	private UUID openJob(UUID productId, String title) {
		return openJob(productId, this.lan, title);
	}

	private UUID openJob(UUID productId, UUID owner, String title) {
		UUID id = UUID.randomUUID();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'OPEN', :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", productId)
			.param("owner", owner)
			.param("title", title)
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.update();
		return id;
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

	private static HttpServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				Runnable before = BEFORE_CONTRIBUTORS.get();
				if (before != null && exchange.getRequestURI().getPath().endsWith("/contributors")) {
					before.run();
				}
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
			throw new java.io.UncheckedIOException(ex);
		}
	}

}
