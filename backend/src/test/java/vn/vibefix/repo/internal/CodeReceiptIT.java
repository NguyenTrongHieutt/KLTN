package vn.vibefix.repo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.job.JobBusy;
import vn.vibefix.job.JobTransitioned;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 23.10a (FR-37, FR-38, D10, AD-24): the code receipt, which is the Customer's own statement that they have
 * the finished code. Its guards are the matrix's -- already recorded, the Final Merge already read back, not the
 * Customer, and no open choice with no accepted work -- and confirming it applies J9 with
 * {@code finish=SELF_DOWNLOAD}: Closed, or Cancelled when Closing was entered by a confirmed cancellation. A Job
 * already Cancelled at J10 with accepted work keeps the path, with no Final Merge card and no second deadline, until
 * its repositories are deleted. No GitHub call is made on this path: the Intermediary's link and the Setup both come
 * from vibefix's own rows.
 * <p>
 * Story 23.10b adds the two shapes with no choice left: a Job the deadline ended as {@code UNCONFIRMED}, read back
 * as that third outcome and still accepting a late receipt, which closes its {@code repo.download_before_deletion}
 * to-do; and a Cancelled Job that never had a choice row at all, which the step serves on its accepted work alone.
 */
class CodeReceiptIT extends IntegrationTest {

	private static final AtomicLong IDS = new AtomicLong(990000);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private FinalMergeAuthorizations authorizations;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private JobBusy busy;

	@Autowired
	private RepoPersonalData personalData;

	private UUID lan;

	private UUID hung;

	private UUID job;

	private UUID product;

	private UUID setup;

	private String intermediary;

	@BeforeEach
	void aClosingCodeJobWithAcceptedWork() {
		this.lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		this.hung = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, null);
		long id = IDS.incrementAndGet();
		UUID product = this.product = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, :name, 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", this.lan).param("github", id)
			.param("name", "quan-may-" + id + "/so-dat-ban").update();
		this.job = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, closing_at)
				VALUES (:id, :product, :owner, 'CLOSING', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 8, :now, :now,
				    :now)
				""").param("id", this.job).param("product", product).param("owner", this.lan).param("mentor", this.hung)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.setup = UUID.randomUUID();
		this.intermediary = "vibefix-dev/job-" + id + "-intermediary";
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, source_full_name, default_branch, head_sha,
				    head_read_at, approved_sha, confirmed_at, intermediary_github_id, intermediary_name, working_github_id,
				    working_name, checked_at, intermediary_at, working_at, copied_at, started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', :source, 'main', :sha, :now, :sha, :now, :intermediaryId, :intermediary,
				    :workingId, :working, :now, :now, :now, :now, :now, :now)
				""").param("id", this.setup).param("job", this.job).param("product", product)
			.param("source", "quan-may-" + id + "/so-dat-ban").param("sha", "a".repeat(40))
			.param("intermediaryId", id + 1).param("intermediary", this.intermediary).param("workingId", id + 2)
			.param("working", "vibefix-dev/job-" + id + "-working").param("now", now).update();
		this.authorizations.open(this.job);
	}

	@Test
	void theReceiptClosesTheJobWithItsOwnFinishAndIsRecordedWithWhoAndWhen() throws Exception {
		acceptedWork();

		receipt(this.lan, 8).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("SELF_DOWNLOAD"))
			.andExpect(jsonPath("$.receipt.confirmedBy").value(this.lan.toString()))
			.andExpect(jsonPath("$.receipt.confirmedAt").isNotEmpty());

		assertThat(this.jdbc.sql("""
				SELECT setup_id || ' ' || confirmed_by FROM repo.code_receipt WHERE job_id = :job
				""").param("job", this.job).query(String.class).single()).isEqualTo(this.setup + " " + this.lan);
		assertThat(jobState()).isEqualTo("CLOSED");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM platform.event_publication WHERE event_type LIKE '%JobTransitioned'
				    AND serialized_event LIKE '%"finish":"SELF_DOWNLOAD"%'
				""").query(Long.class).single()).as("J9 records the finish fact").isPositive();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.code_receipt_recorded' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isOne();
	}

	/**
	 * AD-28: another operation's marker refuses J9, and {@code JobLifecycle.tryApply} hides that refusal, so the
	 * command must refuse too -- nothing may commit, or the choice would be spent on a Job that never closed.
	 */
	@Test
	void anotherOperationsMarkerRefusesTheReceiptAndRecordsNothing() throws Exception {
		acceptedWork();
		this.transactions.executeWithoutResult(status -> this.busy.reserve(this.job, JobBusy.Purpose.SETTLEMENT,
				UUID.randomUUID().toString(), 8, "dispute.settlement:" + UUID.randomUUID()));

		receipt(this.lan, 8).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_BUSY"));

		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.code_receipt WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).as("the receipt rolled back with the refusal").isZero();
		assertThat(authorizationStatus()).as("and the choice is still the Customer's").isEqualTo("REQUESTED");
		assertThat(jobState()).isEqualTo("CLOSING");
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry WHERE action = 'repo.code_receipt_recorded' AND job_id = :job
				""").param("job", this.job).query(Long.class).single()).isZero();
	}

	/** FR-38: a Job whose Closing came from a confirmed cancellation ends Cancelled, with the same fact. */
	@Test
	void theReceiptEndsACancelledClosingAsCancelled() throws Exception {
		acceptedWork();
		this.jdbc.sql("""
				INSERT INTO job.cancellation_proposal (id, job_id, scope, reason, proposer, proposer_account_id, status,
				    answer_by, created_at, decided_at)
				VALUES (:id, :job, 'JOB', 'Không cần nữa', 'CUSTOMER', :lan, 'CONFIRMED', now(), now(), now())
				""").param("id", UUID.randomUUID()).param("job", this.job).param("lan", this.lan).update();

		receipt(this.lan, 8).andExpect(status().isOk());

		assertThat(jobState()).isEqualTo("CANCELLED");
	}

	/**
	 * Story 23.10b (FR-38, D10): the closing choice's deadline ended the Job as {@code UNCONFIRMED}, which the step
	 * reads back as the third outcome with the self-download still open. Confirming the receipt late is recorded,
	 * closes {@code repo.download_before_deletion}, and applies no second J9; a repeat is a conflict.
	 */
	@Test
	void aLateReceiptIsRecordedAndClosesTheDownloadTodo() throws Exception {
		acceptedWork();
		this.transactions.executeWithoutResult(status -> this.authorizations.closing(this.job));
		this.authorizations.endOf(this.job, "DEADLINE");
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = now() WHERE id = :id").param("id", this.job).update();
		endedUnconfirmed();
		await().atMost(Duration.ofSeconds(15)).until(() -> downloadTodos() == 1);

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.endReason").value("DEADLINE"))
			.andExpect(jsonPath("$.outcome").value("UNCONFIRMED"))
			.andExpect(jsonPath("$.choice.finalMerge").value(false))
			.andExpect(jsonPath("$.choice.selfDownload").value(true))
			.andExpect(jsonPath("$.choice.deadlineAt").doesNotExist())
			.andExpect(jsonPath("$.choice.deletionAt").isNotEmpty());
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.hung, Role.MENTOR)))
			.andExpect(jsonPath("$.outcome").value("UNCONFIRMED"))
			.andExpect(jsonPath("$.receipt").doesNotExist())
			.andExpect(jsonPath("$.choice.intermediaryUrl").doesNotExist());

		receipt(this.lan, 8).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SELF_DOWNLOAD"));

		assertThat(downloadTodos()).as("the duty to fetch the code is done").isZero();
		assertThat(jobState()).as("J9 was applied once, by the deadline").isEqualTo("CLOSED");
		assertThat(notifications("repo.code_receipt_unconfirmed")).as("and nothing is said twice").isOne();
		receipt(this.lan, 8).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("CODE_RECEIPT_RECORDED"));
	}

	/**
	 * Story 23.10b (D10): a Code Job Cancelled with work already merged into the Intermediary Repository but no
	 * closing-choice row ever opened is no longer a 404 -- it never had a choice to make, so the step answers with
	 * the self-download alone, and the receipt is accepted.
	 */
	@Test
	void aCancelledJobWithNoClosingChoiceRowStillReceivesItsCode() throws Exception {
		acceptedWork();
		this.jdbc.sql("DELETE FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job).update();
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED', cancelled_at = now() WHERE id = :id")
			.param("id", this.job)
			.update();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(this.job.toString()))
			.andExpect(jsonPath("$.status").value("ENDED"))
			.andExpect(jsonPath("$.endReason").doesNotExist())
			.andExpect(jsonPath("$.outcome").doesNotExist())
			.andExpect(jsonPath("$.merge").doesNotExist())
			.andExpect(jsonPath("$.choice.finalMerge").value(false))
			.andExpect(jsonPath("$.choice.selfDownload").value(true))
			.andExpect(jsonPath("$.choice.deadlineAt").doesNotExist())
			.andExpect(jsonPath("$.choice.intermediaryUrl").isNotEmpty());

		receipt(this.lan, 8).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SELF_DOWNLOAD"));

		assertThat(jobState()).as("no J9 is left to apply").isEqualTo("CANCELLED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.code_receipt WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isOne();
		// The receipt is the Customer's own record: reading the step again must still show it, although there is
		// nothing left to fetch and so no other reason to serve a Job with no row.
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("SELF_DOWNLOAD"))
			.andExpect(jsonPath("$.receipt.confirmedBy").value(this.lan.toString()))
			.andExpect(jsonPath("$.choice.selfDownload").value(false));
	}

	/** A Job with neither a closing-choice row nor work left to fetch has no step at all. */
	@Test
	void aJobWithNeitherARowNorWorkToFetchIsNotFound() throws Exception {
		this.jdbc.sql("DELETE FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job).update();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	/** The step with no row belongs to an ended Job: a running one has no closure to read, work accepted or not. */
	@Test
	void anActiveJobWithAcceptedWorkAndNoRowIsNotFound() throws Exception {
		acceptedWork();
		this.jdbc.sql("DELETE FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job).update();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE', closing_at = NULL WHERE id = :id").param("id", this.job).update();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	/** The repositories are gone and nothing was confirmed: a Job with no row has nothing left to answer with. */
	@Test
	void aNoRowJobWhoseIntermediaryIsDeletedIsNotFound() throws Exception {
		acceptedWork();
		this.jdbc.sql("DELETE FROM repo.final_merge_authorization WHERE job_id = :job").param("job", this.job).update();
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED', cancelled_at = now() WHERE id = :id")
			.param("id", this.job)
			.update();
		this.jdbc.sql("UPDATE repo.job_setup SET deleted_at = now() WHERE id = :id").param("id", this.setup).update();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(status().isNotFound());
	}

	/** D10: a Job Cancelled at J10 with accepted work keeps the self-download alone, until the repositories go. */
	@Test
	void aCancelledJobWithAcceptedWorkKeepsTheSelfDownloadAlone() throws Exception {
		acceptedWork();
		this.authorizations.endOf(this.job, "CANCELLED");
		this.jdbc.sql("UPDATE job.job SET state = 'CANCELLED', cancelled_at = now() WHERE id = :id")
			.param("id", this.job)
			.update();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.status").value("ENDED"))
			.andExpect(jsonPath("$.endReason").value("CANCELLED"))
			.andExpect(jsonPath("$.choice.finalMerge").value(false))
			.andExpect(jsonPath("$.choice.selfDownload").value(true))
			.andExpect(jsonPath("$.choice.deadlineAt").doesNotExist());
		receipt(this.lan, 8).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SELF_DOWNLOAD"));

		assertThat(jobState()).as("no J9 is left to apply").isEqualTo("CANCELLED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.code_receipt WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isOne();
	}

	/** The repositories are deleted: there is nothing left to download, so nothing left to confirm. */
	@Test
	void aDeletedIntermediaryClosesTheSelfDownload() throws Exception {
		acceptedWork();
		this.jdbc.sql("UPDATE repo.job_setup SET deleted_at = now() WHERE id = :id").param("id", this.setup).update();

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.choice.selfDownload").value(false))
			.andExpect(jsonPath("$.choice.intermediaryUrl").doesNotExist());
		receipt(this.lan, 8).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NO_CLOSING_CHOICE_OPEN"));
	}

	/** The choice belongs to Closing: an Active Job with a proposal open has nothing to receive yet. */
	@Test
	void anActiveJobHasNoClosingChoiceToConfirm() throws Exception {
		acceptedWork();
		this.jdbc.sql("UPDATE job.job SET state = 'ACTIVE', closing_at = NULL WHERE id = :id").param("id", this.job).update();

		receipt(this.lan, 8).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NO_CLOSING_CHOICE_OPEN"));
	}

	/** A Job whose closing choice ended with no accepted work: the Intermediary holds nothing the Customer accepted. */
	@Test
	void aJobWithoutAcceptedWorkHasNothingToConfirm() throws Exception {
		this.authorizations.endOf(this.job, "DEADLINE");

		receipt(this.lan, 8).andExpect(status().isConflict())
			.andExpect(jsonPath("$.reason").value("NO_CLOSING_CHOICE_OPEN"));
	}

	/** The receipt is the Customer's own statement: nobody else may make it, and the Mentor finds no command. */
	@Test
	void onlyTheCustomerConfirmsTheReceipt() throws Exception {
		acceptedWork();

		receipt(this.hung, 8).andExpect(status().isNotFound());

		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.code_receipt WHERE job_id = :job").param("job", this.job)
			.query(Long.class).single()).isZero();
	}

	/** AD-24: the receipt names an account, so an erasure takes it, and a Product deletion takes it with its Setup. */
	@Test
	void anErasureAndAProductDeletionBothTakeTheReceipt() throws Exception {
		acceptedWork();
		receipt(this.lan, 8).andExpect(status().isOk());
		assertThat(receipts()).isOne();

		this.transactions.executeWithoutResult(status -> this.personalData.eraseAccount(this.lan));

		assertThat(receipts()).as("nothing in it to anonymize: it says only who confirmed, and when").isZero();

		this.jdbc.sql("""
				INSERT INTO repo.code_receipt (job_id, setup_id, confirmed_by, confirmed_at)
				VALUES (:job, :setup, :by, now())
				""").param("job", this.job).param("setup", this.setup).param("by", this.hung).update();
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.product));

		assertThat(receipts()).as("the Setup's foreign key does not block the Product's deletion").isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM repo.job_setup WHERE id = :id").param("id", this.setup)
			.query(Long.class).single()).isZero();
	}

	/**
	 * EXPERIENCE.md's Deadline chip: the choice's deadline warns inside 48 h, and the server decides that, so the
	 * chip cannot read the clock itself.
	 */
	@Test
	void theChoicesDeadlineWarnsOnlyInsideFortyEightHours() throws Exception {
		acceptedWork();
		this.transactions.executeWithoutResult(status -> this.authorizations.closing(this.job));

		this.clock.advance(Duration.ofDays(7).minus(CodeReceipts.SOON).minusMinutes(1));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.choice.deadlineAt").isNotEmpty())
			.andExpect(jsonPath("$.choice.near").value(false));

		this.clock.advance(Duration.ofMinutes(2));
		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.choice.near").value(true));
	}

	/** The deadline runs while the Job is Closing: once it ends, the chip has no date left to warn about. */
	@Test
	void anEndedJobReportsNoDeadline() throws Exception {
		acceptedWork();
		this.transactions.executeWithoutResult(status -> this.authorizations.closing(this.job));
		receipt(this.lan, 8).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/jobs/" + this.job + "/final-merge").with(as(this.lan, Role.CUSTOMER)))
			.andExpect(jsonPath("$.choice.deadlineAt").doesNotExist())
			.andExpect(jsonPath("$.choice.near").value(false));
	}

	private long receipts() {
		return this.jdbc.sql("SELECT count(*) FROM repo.code_receipt WHERE job_id = :job")
			.param("job", this.job)
			.query(Long.class)
			.single();
	}

	/** What J9 published when the closing choice's deadline ended the Job with neither path taken (Story 23.10b). */
	private void endedUnconfirmed() {
		this.transactions.executeWithoutResult(status -> this.events.publishEvent(new JobTransitioned(UUID.randomUUID(),
				UUID.randomUUID(), this.job, "J9", "SYSTEM", "CLOSING", "CLOSED", 8, 1, Map.of("finish", "UNCONFIRMED"))));
	}

	private long downloadTodos() {
		return this.jdbc.sql("""
				SELECT count(*) FROM notification.todo WHERE todo_key = 'repo.download_before_deletion'
				    AND account_id = :id AND closed_at IS NULL
				""").param("id", this.lan).query(Long.class).single();
	}

	private long notifications(String type) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = :type AND account_id = :id")
			.param("type", type)
			.param("id", this.lan)
			.query(Long.class)
			.single();
	}

	/** One accepted handover merged into the Intermediary default branch: the code the Customer may take. */
	private void acceptedWork() {
		this.jdbc.sql("""
				INSERT INTO repo.handover_merge (job_id, pull_number, handover_sha, head_after, merged_at)
				VALUES (:job, 1, :sha, :sha, :now)
				""").param("job", this.job).param("sha", "b".repeat(40))
			.param("now", Timestamp.from(this.clock.instant())).update();
	}

	private ResultActions receipt(UUID account, long version) throws Exception {
		Role role = account.equals(this.lan) ? Role.CUSTOMER : Role.MENTOR;
		return this.mvc.perform(post("/api/v1/jobs/" + this.job + "/code-receipt").with(as(account, role)).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true,\"version\":" + version + "}"));
	}

	private String jobState() {
		return this.jdbc.sql("SELECT state FROM job.job WHERE id = :id").param("id", this.job).query(String.class).single();
	}

	private String authorizationStatus() {
		return this.jdbc.sql("SELECT status FROM repo.final_merge_authorization WHERE job_id = :job")
			.param("job", this.job)
			.query(String.class)
			.single();
	}

	private RequestPostProcessor as(UUID account, Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
