package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.github.ActionsRuns;
import vn.vibefix.github.internal.ActionsExchangeSupport;
import vn.vibefix.github.internal.GitHubStub;
import vn.vibefix.github.internal.TestKeys;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobCompletion;
import vn.vibefix.operations.Lane;
import vn.vibefix.operations.internal.OperationWorker;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 23.9a (FR-37, AD-5, AD-11, AD-44, AD-46, R1, D7, SG-9, SG-19): a Code Job in Closing gets its Final Merge
 * check -- the Source Repository read again through the Customer's Source App, every team grant revoked, J9's guard,
 * the whole Job's Sensitive Changes, and the two {@code .github/workflows/} trees. The Customer confirms N-18, and
 * one run of the published {@code vibefix.yml} on their own GitHub Actions pushes the Intermediary's merge commit to
 * a new {@code vibefix/final-{jobId}} branch. vibefix reads that branch back, applies J9 and tells both sides; the
 * Customer merges the branch themselves on GitHub. vibefix opens no pull request and writes nothing in the Customer's
 * repository. A workflow-file difference, an existing branch, Actions' quota or a failed run names its reason and
 * keeps the closure fallback offered. GitHub and git hosting are {@link GitHubStub}; the stub runs no workflow, so a
 * test pushes what the run would.
 */
class FinalMergeIT extends IntegrationTest {

	private static final Path DIRECTORY;

	private static final GitHubStub GITHUB;

	private static final AtomicLong IDS = new AtomicLong(770000);

	static {
		try {
			DIRECTORY = Files.createTempDirectory("final-merge-it-");
			GITHUB = new GitHubStub(Files.createDirectories(DIRECTORY.resolve("git")));
			// AD-46: the runner the organization read-back requires (Story 23.3).
			GITHUB.runner("vibefix-runner");
			// Story 23.4: the stub stands in for GitHub Actions' OIDC issuer, whose ID tokens a run exchanges.
			ActionsExchangeSupport.issue(GITHUB);
			Files.writeString(DIRECTORY.resolve("app-key"), TestKeys.pkcs1());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private OperationWorker worker;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private FinalMergeAuthorizations authorizations;

	@Autowired
	private ActionsRuns runs;

	@Autowired
	private JobCompletion completion;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private String owner;

	private String name;

	private long repository;

	private long ownerId;

	private String intermediary;

	private long intermediaryId;

	private String handed;

	@DynamicPropertySource
	static void github(DynamicPropertyRegistry registry) {
		registry.add("vibefix.github.api-url", () -> GITHUB.apiUrl().toString());
		registry.add("vibefix.github.git-url", () -> GITHUB.gitUrl().toString());
		registry.add("vibefix.github.platform-app.id", () -> "123456");
		registry.add("vibefix.github.platform-app.installation-id", () -> String.valueOf(GitHubStub.INSTALLATION));
		registry.add("vibefix.github.platform-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		registry.add("vibefix.github.runner-repository", () -> "vibefix-runner");
		// Story 23.5: the Source checks, the dispatch and the branch read-back go through the Customer's Source App.
		registry.add("vibefix.github.source-app.id", () -> "2222");
		registry.add("vibefix.github.source-app.key-file", () -> DIRECTORY.resolve("app-key").toString());
		// Story 23.4: the run reaches the exchange through the dev stack's tunnel.
		registry.add("vibefix.github.exchange.public-url", () -> "https://exchange.example.test");
		registry.add("vibefix.github.exchange.issuer", () -> GITHUB.apiUrl().toString());
	}

	@AfterAll
	static void stop() {
		GITHUB.close();
	}

	@BeforeEach
	void aClosingCodeJobWithOneMergedHandover() throws Exception {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		long id = IDS.incrementAndGet();
		// The Customer's own repository: GitHub shows them as its owner and administrator.
		this.ownerId = this.jdbc.sql("SELECT github_user_id FROM identity.account WHERE id = :id").param("id", this.lan)
			.query(Long.class)
			.single();
		this.owner = "quan-may-" + id;
		this.name = "so-dat-ban";
		this.repository = id;
		GITHUB.source(this.owner, this.name);
		GITHUB.publish(this.owner, this.name, this.repository, this.ownerId, true, true);
		// The Customer's Source App on exactly the Source Repository, and the published vibefix.yml on its default
		// branch, which the dispatch needs there (Story 23.5); the Intermediary is copied from it, so both trees hold
		// the same workflow files.
		GITHUB.installSource(this.owner, this.name, this.repository, this.ownerId);
		GITHUB.push(this.owner, this.name, "main", ".github/workflows/vibefix.yml",
				GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		GITHUB.sourceFiles.put(this.owner + "/" + this.name + ":.github/workflows/vibefix.yml",
				GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW));
		String approved = GITHUB.head(this.owner, this.name, "main");
		this.intermediary = "job-" + id + "-intermediary";
		this.intermediaryId = GITHUB.platformRepository(this.intermediary, this.owner, this.name);
		long workingId = GITHUB.platformRepository("job-" + id + "-working", GitHubStub.ORGANIZATION, this.intermediary);
		// One accepted handover, merged into the Intermediary default branch by repo.
		this.handed = GITHUB.push(GitHubStub.ORGANIZATION, this.intermediary, "main", "package.json",
				"{\"name\":\"quan-may\"}\n", "Bàn giao Giai đoạn 1", "hung@example.test");

		UUID product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", this.repository)
			.param("name", this.owner + "/" + this.name).update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, closing_at)
				VALUES (:id, :product, :owner, 'CLOSING', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 8, :now, :now, :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, head_sha,
				    head_read_at, approved_sha, confirmed_at, intermediary_github_id, intermediary_name, working_github_id,
				    working_name, checked_at, intermediary_at, working_at, copied_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :now, :sha, :now, :intermediaryId, :intermediary,
				    :workingId, :working, :now, :now, :now, :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", this.job).param("product", product)
			.param("source", this.owner + "/" + this.name).param("sha", approved)
			.param("intermediaryId", this.intermediaryId)
			.param("intermediary", GitHubStub.ORGANIZATION + "/" + this.intermediary).param("workingId", workingId)
			.param("working", GitHubStub.ORGANIZATION + "/job-" + id + "-working").param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO repo.handover_merge (job_id, pull_number, handover_sha, head_after, merged_at)
				VALUES (:job, 1, :sha, :sha, :now)
				""").param("job", this.job).param("sha", this.handed).param("now", now).update();

		this.authorizations.open(this.job);
	}

	@Test
	void theCheckThenTheRunPushesTheBranchWhichIsReadBackAndTheJobCloses() throws Exception {
		check();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.merge.status").value("READY"))
			.andExpect(jsonPath("$.merge.sensitive[0].path").value("package.json"))
			.andExpect(jsonPath("$.merge.sensitive[0].kind").value("DEPENDENCIES"))
			.andExpect(jsonPath("$.merge.workflows").isEmpty())
			.andExpect(jsonPath("$.merge.run").doesNotExist());
		confirm(false).andExpect(status().isBadRequest());
		confirm(true).andExpect(status().isAccepted()).andExpect(jsonPath("$.merge.status").value("CONFIRMED"));
		assertThat(this.jdbc.sql("SELECT busy_purpose FROM job.job WHERE id = :id").param("id", this.job).query(String.class)
			.single()).isEqualTo("FINAL_MERGE");
		assertThat(openTodos()).isZero();
		confirm(true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("FINAL_MERGE_CONFIRMED"));

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("RUNNING");
		UUID correlation = correlation();
		ActionsRuns.Correlation recorded = this.runs.correlation(correlation).orElseThrow();
		assertThat(recorded.operation()).isEqualTo(ActionsRuns.Operation.FINAL_MERGE);
		assertThat(recorded.repositoryId()).as("the run is in the Customer's own repository").isEqualTo(this.repository);
		assertThat(recorded.grant()).as("Contents read on the Intermediary alone (AD-44, NFR-5)")
			.containsExactly(this.intermediaryId);
		assertThat(recorded.confirmedHeadSha()).as("the SHA check is the copy's alone (AD-44)").isNull();
		assertThat(GITHUB.runs.get(runId()).get("inputs")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
			.containsEntry("operation", "final-merge")
			.containsEntry("sha", this.handed)
			.containsEntry("branch", "vibefix/final-" + this.job)
			.containsEntry("intermediary", GitHubStub.ORGANIZATION + "/" + this.intermediary)
			.doesNotContainKey("working");

		runPushesTheBranch();

		assertThat(mergeStatus()).isEqualTo("DONE");
		assertThat(authorizationStatus()).isEqualTo("USED");
		assertThat(jobState()).isEqualTo("CLOSED");
		assertThat(released()).as("the marker is released").isTrue();
		assertThat(GITHUB.head(this.owner, this.name, "vibefix/final-" + this.job)).as("the branch the Customer merges")
			.isEqualTo(this.handed);
		assertThat(GITHUB.head(this.owner, this.name, "main")).as("vibefix merges nothing itself")
			.isNotEqualTo(this.handed);
		assertThat(GITHUB.sourcePulls.getOrDefault(this.owner + "/" + this.name, Map.of()))
			.as("vibefix opens no pull request in a Customer repository")
			.isEmpty();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM notification.notification WHERE type = 'repo.final_branch_ready'
				    AND account_id IN (:lan, :hung)
				""").param("lan", this.lan).param("hung", this.hung).query(Long.class).single()).isEqualTo(2);
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("USED"))
			.andExpect(jsonPath("$.merge.status").value("DONE"))
			.andExpect(jsonPath("$.merge.branch").value("vibefix/final-" + this.job))
			.andExpect(jsonPath("$.merge.run.state").value("COMPLETED"))
			.andExpect(jsonPath("$.merge.run.conclusion").value("success"))
			.andExpect(jsonPath("$.merge.run.htmlUrl").value(GITHUB.runs.get(runId()).get("html_url")))
			.andExpect(jsonPath("$.merge.branchUrl")
				.value("https://github.com/" + this.owner + "/" + this.name + "/tree/vibefix/final-" + this.job))
			.andExpect(jsonPath("$.merge.compareUrl").value("https://github.com/" + this.owner + "/" + this.name
					+ "/compare/main...vibefix/final-" + this.job + "?expand=1"));
	}

	/**
	 * deferred-work 626: a run's own token can never push a file under {@code .github/workflows/}, so a difference
	 * between the Source Repository's tree and the accepted code's replaces N-18's checkbox and nothing is dispatched.
	 */
	@Test
	void aWorkflowFileThatDiffersBlocksTheRunAndNamesItsReason() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.intermediary, "main", ".github/workflows/ci.yml",
				"on: push\njobs: {}\n", "Bàn giao Giai đoạn 2", "hung@example.test");
		String head = GITHUB.head(GitHubStub.ORGANIZATION, this.intermediary, "main");
		// The runtime role may not update repo.handover_merge, so the second handover's merge is a row of its own.
		this.jdbc.sql("""
				INSERT INTO repo.handover_merge (job_id, pull_number, handover_sha, head_after, merged_at)
				VALUES (:job, 2, :sha, :sha, :now)
				""").param("job", this.job).param("sha", head).param("now", Timestamp.from(this.clock.instant())).update();

		check();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.merge.status").value("READY"))
			.andExpect(jsonPath("$.merge.workflows[0]").value(".github/workflows/ci.yml"));
		confirm(true).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("WORKFLOW_FILES_CHANGED"));
		assertThat(sourceRuns()).as("no minute of the Customer's Actions is spent").isEmpty();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("the fallback stays the Customer's own choice").isZero();
	}

	/** A push vibefix never observed: the branch is read before each dispatch, so J9 follows without a second run. */
	@Test
	void aBranchAlreadyAtTheMergeCommitFinishesWithoutASecondRun() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		GITHUB.pushCommit(GitHubStub.ORGANIZATION, this.intermediary, this.handed, this.owner, this.name,
				"vibefix/final-" + this.job);

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("DONE");
		assertThat(jobState()).isEqualTo("CLOSED");
		assertThat(sourceRuns()).as("nothing was dispatched").isEmpty();
	}

	@Test
	void aBranchAtAnotherCommitEndsTheFinalMergeAndOffersTheFallback() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		GITHUB.pushCommit(GitHubStub.ORGANIZATION, this.intermediary, firstCommit(), this.owner, this.name,
				"vibefix/final-" + this.job);

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(refusal()).isEqualTo("BRANCH_EXISTS");
		assertThat(authorizationStatus()).isEqualTo("ENDED");
		assertThat(released()).isTrue();
		assertThat(sourceRuns()).isEmpty();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge'")
			.query(String.class).single()).isEqualTo("ABORTED");
		zipFallback();
		assertThat(this.jdbc.sql("SELECT reason || ':' || status FROM repo.zip_fallback WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("MERGE_REFUSED:READY");
		assertThat(jobState()).isEqualTo("CLOSED");
	}

	/** R20: a run Actions' quota stopped names its reason at once, with the fallback still offered. */
	@Test
	void aRunActionsQuotaStoppedEndsTheFinalMergeWithItsReason() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.worker.runDue(Lane.GITHUB_LONG);
		GITHUB.failBeforeAnyJob(runId());
		this.clock.advance(FinalMergeOperation.RUN_POLL.plusSeconds(1));

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(refusal()).isEqualTo("ACTIONS_QUOTA");
		assertThat(released()).isTrue();
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.merge.refusal").value("ACTIONS_QUOTA"))
			.andExpect(jsonPath("$.merge.run.conclusion").value("failure"))
			.andExpect(jsonPath("$.merge.branchUrl").doesNotExist());
	}

	/**
	 * AD-44: a run vibefix's exchange refused pushes nothing, and a second would be refused the same way, so the
	 * Final Merge ends {@code EXCHANGE_REFUSED} with the fallback still offered.
	 */
	@Test
	void anExchangeRefusalEndsTheFinalMergeAndOffersTheFallback() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.worker.runDue(Lane.GITHUB_LONG);
		// A run someone else started: its ID token is refused, so no token ever reaches it (Story 23.4).
		GITHUB.runs.get(runId()).put("triggering_actor", Map.of("login", "mallory", "type", "User"));
		exchange().andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("RUN"));
		this.clock.advance(FinalMergeOperation.RUN_POLL.plusSeconds(1));

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(refusal()).isEqualTo("EXCHANGE_REFUSED");
		assertThat(released()).isTrue();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge'")
			.query(String.class).single()).isEqualTo("ABORTED");
		zipFallback();
		assertThat(this.jdbc.sql("SELECT reason || ':' || status FROM repo.zip_fallback WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("MERGE_REFUSED:READY");
	}

	/**
	 * AD-46: a run still out after {@link ActionsRuns#RUN_TIMEOUT} is given up -- its correlation closed, so it can
	 * never exchange, and its run cancelled -- and another is dispatched under a new correlation.
	 */
	@Test
	void aRunThatDoesNotCompleteWithinItsTimeoutIsClosedCancelledAndDispatchedAgain() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.worker.runDue(Lane.GITHUB_LONG);
		UUID first = correlation();
		long firstRun = runId();
		GITHUB.startRun(firstRun);
		this.clock.advance(FinalMergeOperation.RUN_POLL.plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(runStatus()).isEqualTo("IN_PROGRESS");

		this.clock.advance(ActionsRuns.RUN_TIMEOUT);
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(runConclusion()).isEqualTo(FinalMergeOperation.TIMED_OUT);
		assertThat(this.jdbc.sql("""
				SELECT status || ':' || error_code FROM operations.external_operation WHERE type = 'repo.final_merge'
				""").query(String.class).single()).isEqualTo("PENDING:RUN_FAILED");
		assertThat(this.runs.correlation(first).orElseThrow().status()).as("closed")
			.isEqualTo(ActionsRuns.Status.EXPIRED);
		assertThat(GITHUB.seen).as("and its run cancelled").anyMatch(request -> request.method().equals("POST")
				&& request.path().equals("/repos/" + this.owner + "/" + this.name + "/actions/runs/" + firstRun + "/cancel"));
		exchange(first, firstRun).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.reason").value("CORRELATION"));

		this.clock.advance(FinalMergeOperation.POLICY.delayAfter(1).plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(correlation()).as("a second run, under its own correlation").isNotEqualTo(first);
		runPushesTheBranch();

		assertThat(mergeStatus()).isEqualTo("DONE");
	}

	/** A run that succeeded but whose branch does not read back is spent: another is dispatched, and that one reads. */
	@Test
	void aSuccessfulRunWhoseBranchDoesNotReadBackIsDispatchedAgain() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.worker.runDue(Lane.GITHUB_LONG);
		UUID first = correlation();
		// The run concluded success and pushed nothing: the branch is not there.
		GITHUB.completeRun(runId(), "success");
		this.clock.advance(FinalMergeOperation.RUN_POLL.plusSeconds(1));

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(runConclusion()).isEqualTo(FinalMergeOperation.NOT_READ_BACK);
		assertThat(mergeStatus()).as("not done: nothing was read back").isEqualTo("RUNNING");
		assertThat(this.jdbc.sql("""
				SELECT status || ':' || error_code FROM operations.external_operation WHERE type = 'repo.final_merge'
				""").query(String.class).single()).isEqualTo("PENDING:RUN_FAILED");
		assertThat(jobState()).isEqualTo("CLOSING");

		this.clock.advance(FinalMergeOperation.POLICY.delayAfter(1).plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);
		assertThat(correlation()).isNotEqualTo(first);
		runPushesTheBranch();

		assertThat(mergeStatus()).isEqualTo("DONE");
	}

	/**
	 * AD-6: once the attempts run out the Final Merge ends and the Job follows the closure fallback -- the step that
	 * cannot be undone. A run still queued is given up with it, so it can never exchange and push afterwards, and a
	 * vibefix-side code is stored as {@code RUN_FAILED}, the only reason the closure section can word.
	 */
	@Test
	void theAttemptsRunningOutEndsTheFinalMergeAndGivesUpTheRunStillQueued() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.worker.runDue(Lane.GITHUB_LONG);
		UUID correlation = correlation();
		long run = runId();
		// GitHub stops answering the Source read, so every later attempt fails before it looks at the run.
		GITHUB.unavailable.add("GET /repos/" + this.owner + "/" + this.name);
		try {
			for (int attempt = 1; attempt <= FinalMergeOperation.POLICY.maxAttempts(); attempt++) {
				this.clock.advance(FinalMergeOperation.POLICY.delayAfter(attempt).plusSeconds(1));
				this.worker.runDue(Lane.GITHUB_LONG);
			}
		}
		finally {
			GITHUB.unavailable.clear();
		}
		awaitListeners();

		assertThat(this.jdbc.sql("""
				SELECT status || ':' || error_code FROM operations.external_operation WHERE type = 'repo.final_merge'
				""").query(String.class).single()).isEqualTo("FAILED:GITHUB_HTTP_502");
		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(refusal()).as("the closure section words only its own reasons").isEqualTo("RUN_FAILED");
		assertThat(released()).as("the marker is released").isTrue();
		assertThat(this.runs.correlation(correlation).orElseThrow().status()).as("the queued run is given up")
			.isEqualTo(ActionsRuns.Status.EXPIRED);
		assertThat(GITHUB.seen).as("and cancelled, so it never pushes after the fallback")
			.anyMatch(request -> request.method().equals("POST")
					&& request.path().equals("/repos/" + this.owner + "/" + this.name + "/actions/runs/" + run + "/cancel"));
		zipFallback();
		assertThat(this.jdbc.sql("SELECT reason || ':' || status FROM repo.zip_fallback WHERE job_id = :job")
			.param("job", this.job).query(String.class).single()).isEqualTo("MERGE_REFUSED:READY");
		assertThat(jobState()).isEqualTo("CLOSED");
	}

	/** A run that failed for another reason is dispatched again under a new correlation (AD-6). */
	@Test
	void aFailedRunIsDispatchedAgainUnderANewCorrelation() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.worker.runDue(Lane.GITHUB_LONG);
		UUID first = correlation();
		GITHUB.completeRun(runId(), "failure");
		this.clock.advance(FinalMergeOperation.RUN_POLL.plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).as("still running: the lane tries again").isEqualTo("RUNNING");
		this.clock.advance(FinalMergeOperation.POLICY.delayAfter(1).plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(correlation()).as("a second run, under its own correlation").isNotEqualTo(first);
		runPushesTheBranch();
		assertThat(mergeStatus()).isEqualTo("DONE");
	}

	/** Story 9.4 (AD-28): an open Dispute pauses the dispatch; nothing runs on the Customer's Actions meanwhile. */
	@Test
	void anOpenDisputePausesTheDispatch() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.jdbc.sql("UPDATE job.job SET frozen_at = :now WHERE id = :id").param("now", Timestamp.from(this.clock.instant()))
			.param("id", this.job).update();

		this.worker.runDue(Lane.GITHUB_LONG);

		assertThat(mergeStatus()).isEqualTo("CONFIRMED");
		assertThat(sourceRuns()).isEmpty();
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge'")
			.query(String.class).single()).isEqualTo("PENDING");

		this.jdbc.sql("UPDATE job.job SET frozen_at = NULL WHERE id = :id").param("id", this.job).update();
		this.clock.advance(FinalMergeOperation.FROZEN_WAIT.plusMinutes(1));
		this.worker.runDue(Lane.GITHUB_LONG);
		runPushesTheBranch();

		assertThat(mergeStatus()).isEqualTo("DONE");
	}

	@Test
	void theCustomerMayChooseTheFallbackUntilTheyConfirm() throws Exception {
		check();
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/decline").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ENDED"))
			.andExpect(jsonPath("$.endReason").value("DECLINED"));
		zipFallback();
		assertThat(this.jdbc.sql("SELECT reason FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job)
			.query(String.class).single()).isEqualTo("DECLINED");
		assertThat(jobState()).isEqualTo("CLOSED");
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/decline").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NO_AUTHORIZATION_OPEN"));
	}

	@Test
	void aConfirmedFinalMergeCanNoLongerBeDeclined() throws Exception {
		check();
		confirm(true).andExpect(status().isAccepted());
		this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/decline").with(as(this.lan, Role.CUSTOMER)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("FINAL_MERGE_CONFIRMED"));
	}

	@Test
	void aHeadThatMovedOutsideTheHandoversFailsTheGuard() throws Exception {
		GITHUB.push(GitHubStub.ORGANIZATION, this.intermediary, "main", "extra.txt", "x\n");
		check();

		assertThat(mergeStatus()).isEqualTo("REFUSED");
		assertThat(refusal()).isEqualTo("J9_GUARD");
	}

	@Test
	void anAccessNotYetRevokedMakesTheCheckWait() throws Exception {
		this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, repository_github_id,
				    repository_name, crew_change, status, created_at)
				SELECT :id, :job, product_id, :mentor, 'MENTOR', 'hung', 1, 'x/y', false, 'INVITING', now()
				FROM job.job WHERE id = :job
				""").param("id", UUID.randomUUID()).param("job", this.job).param("mentor", this.hung).update();
		check();

		assertThat(mergeStatus()).isEqualTo("CHECKING");
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge_check'")
			.query(String.class).single()).isEqualTo("PENDING");
	}

	/** Story 23.7 (R5): the Customer's read of the Intermediary lasts until the deletion, so "all revoked" ignores it. */
	@Test
	void theCustomersLiveReadIsNotCountedAmongTheRevokedAccesses() throws Exception {
		this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, repository_github_id,
				    repository_name, crew_change, status, accepted_at, created_at)
				SELECT :id, :job, product_id, :customer, 'CUSTOMER', 'lan', 2, 'x/intermediary', false, 'ACCEPTED', now(), now()
				FROM job.job WHERE id = :job
				""").param("id", UUID.randomUUID()).param("job", this.job).param("customer", this.lan).update();
		check();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.merge.status").value("READY"));
	}

	@Test
	void anUninstalledSourceAppMakesTheCheckWaitForTheCustomerWithNoFallback() throws Exception {
		// The Customer uninstalls the Source App during Closing: they can install it again, so nothing ends.
		Long source = GITHUB.repositoryInstallations.remove((this.owner + "/" + this.name).toLowerCase(java.util.Locale.ROOT));
		assertThat(source).isNotNull();
		GITHUB.mergeInstallations.remove(source);
		check();

		waitsForTheCustomer();
	}

	@Test
	void aChangedWorkflowMakesTheCheckWaitForTheCustomerWithNoFallback() throws Exception {
		GITHUB.sourceFiles.put(this.owner + "/" + this.name + ":.github/workflows/vibefix.yml",
				GitHubStub.published(GitHubStub.CUSTOMER_WORKFLOW) + "# changed\n");
		check();

		waitsForTheCustomer();
	}

	@Test
	void aCodeJobClosesOnlyWithItsFinishAndEndsCancelledAfterACancellation() {
		assertThat(jobState()).isEqualTo("CLOSING");
		this.jdbc.sql("""
				INSERT INTO job.cancellation_proposal (id, job_id, scope, reason, proposer, proposer_account_id, status, answer_by,
				    created_at, decided_at)
				VALUES (:id, :job, 'JOB', 'Không cần nữa', 'CUSTOMER', :lan, 'CONFIRMED', now(), now(), now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("lan", this.lan).update();
		Boolean finished = this.transactions
			.execute(status -> this.completion.finish(this.job, JobCompletion.Finish.ZIP_FALLBACK, null));
		assertThat(finished).isTrue();
		assertThat(jobState()).isEqualTo("CANCELLED");
	}

	/** A Source block the Customer can fix: the check waits, the row stays, and no fallback starts. */
	private void waitsForTheCustomer() {
		assertThat(mergeStatus()).isEqualTo("CHECKING");
		assertThat(authorizationStatus()).isEqualTo("REQUESTED");
		assertThat(this.jdbc.sql("SELECT status FROM operations.external_operation WHERE type = 'repo.final_merge_check'")
			.query(String.class).single()).isEqualTo("PENDING");
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
	}

	/** The Customer opens JOB-1, which starts the check, and the check runs. */
	private void check() throws Exception {
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk());
		assertThat(mergeStatus()).as("opening JOB-1 in Closing starts the check").isEqualTo("CHECKING");
		this.worker.runDue(Lane.GITHUB_LONG);
	}

	/** What the run on the Customer's Actions leaves behind, since the stub runs no workflow; then the read-back. */
	private void runPushesTheBranch() throws Exception {
		GITHUB.pushCommit(GitHubStub.ORGANIZATION, this.intermediary, this.handed, this.owner, this.name,
				"vibefix/final-" + this.job);
		GITHUB.completeRun(runId(), "success");
		this.clock.advance(FinalMergeOperation.RUN_POLL.plusSeconds(1));
		this.worker.runDue(Lane.GITHUB_LONG);
	}

	/** Every listener of what the worker published has run, so the fallback's own operation is requested. */
	private void awaitListeners() {
		org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(15))
			.until(() -> this.jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
				.query(Long.class)
				.single() == 0);
	}

	/** The fallback the ended row starts, built by its operation. */
	private void zipFallback() {
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc
			.sql("SELECT count(*) FROM repo.zip_fallback WHERE job_id = :job").param("job", this.job).query(Long.class)
			.single() == 1);
		this.worker.runDue(Lane.GITHUB_LONG);
	}

	private ResultActions confirm(boolean agreed) throws Exception {
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/final-merge/confirm").with(as(this.lan, Role.CUSTOMER))
			.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"agreed\":" + agreed + "}"));
	}

	/** The runs the stub holds for the Customer's own repository. */
	private List<Long> sourceRuns() {
		String full = this.owner + "/" + this.name;
		synchronized (GITHUB.runs) {
			return GITHUB.runs.entrySet()
				.stream()
				.filter(run -> full.equals(run.getValue().get("repository_full_name")))
				.map(Map.Entry::getKey)
				.toList();
		}
	}

	private UUID correlation() {
		return this.jdbc.sql("SELECT correlation_id FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(UUID.class).single();
	}

	private String runStatus() {
		return this.jdbc.sql("SELECT run_status FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	private String runConclusion() {
		return this.jdbc.sql("SELECT run_conclusion FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	/** The run's own {@code POST /github/actions/exchange}, as GitHub Actions makes it (Story 23.4). */
	private ResultActions exchange() throws Exception {
		return exchange(correlation(), runId());
	}

	private ResultActions exchange(UUID correlation, long run) throws Exception {
		return this.mvc.perform(ActionsExchangeSupport.exchange(
				ActionsExchangeSupport.idToken(GITHUB, this.clock.instant(), run, this.repository), correlation));
	}

	private long runId() {
		Long id = this.runs.correlation(correlation()).orElseThrow().runId();
		assertThat(id).as("the run was found").isNotNull();
		return id;
	}

	/** The J7 commit: the Intermediary's default branch before the handover merge. */
	private String firstCommit() {
		return this.jdbc.sql("SELECT approved_sha FROM repo.job_setup WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	private boolean released() {
		return this.jdbc.sql("SELECT busy_operation_key IS NULL FROM job.job WHERE id = :id").param("id", this.job)
			.query(Boolean.class).single();
	}

	private String mergeStatus() {
		return this.jdbc.sql("SELECT status FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(String.class).optional().orElse("NONE");
	}

	private String refusal() {
		return this.jdbc.sql("SELECT refusal FROM repo.final_merge WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	private String authorizationStatus() {
		return this.jdbc.sql("SELECT status FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job)
			.query(String.class).single();
	}

	private String jobState() {
		return this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single();
	}

	private long openTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.authorize_final_merge' AND account_id = :id
				    AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
