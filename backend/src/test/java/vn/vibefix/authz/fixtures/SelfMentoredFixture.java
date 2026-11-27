package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.JobContent;
import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixRow;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;
import vn.vibefix.support.TestPeople;

/**
 * The mode world (AD-20, AD-47, Story 25.2a): the Jobs of a Customer who mentors them, seeded only for a row that runs
 * there ({@link MatrixWorld#selfMode()}), since every row seeds every fixture. Each value is named as the crew Job's
 * twin with {@code self} before it ({@link MatrixRow#selfName}), which is how a {@code job=self} row reads it.
 * <p>
 * Its first Job ({@code {selfJobId}}, also {@code {selfBoardId}} and, while it recruits, {@code {selfPostId}}) is a
 * Self-mentored Code Job in Active whose Customer is {@link MatrixCaller#CUSTOMER_MENTOR}: the mode column says so, the
 * Customer is recorded as its Mentor with the notice read, and it has no selected bid, no Job Chat and no contact
 * grant. Its Crew has one member per AD-41 state, the mode world's {@link MatrixCaller#CREW_ASSIGNED} (picked from
 * their Crew Application), {@link MatrixCaller#CREW_ACTIVE}, {@link MatrixCaller#CREW_FORMER} and
 * {@link MatrixCaller#LOCKED} (both released {@code LEFT}), and the split parties
 * {@link MatrixCaller#CREW_ACTIVE_PARTY} and {@link MatrixCaller#CREW_FORMER_PARTY}. Recruitment is on for one more
 * place, with a pending Crew Application ({@code {selfCrewApplicationId}}) of someone who is no caller. The Crew Chat
 * holds the Customer's messages and a file. The confirmed Agreement, which the Customer confirmed alone, has an
 * External Access item and two Milestones: the first accepted, its release waiting in its pool; the second Funded by
 * the Customer's paid order and handed over by the active member. An open Dispute about the second froze the Job;
 * {@link MatrixCaller#ADMIN_DISPUTE_GRANT} took it. As on the crew Job, the Ratings are published whatever the Job's
 * state: the active member's Team Issue waits for the Customer's answer, and {@link MatrixCaller#ADMIN_TEAM_ISSUE_GRANT}
 * took another member's escalated one. The Customer's due-date Change Request is open.
 * The released Milestone's split is out for confirmation and a third Milestone's is held by an open Split Resolution,
 * which {@link MatrixCaller#ADMIN_SPLIT_RESOLUTION_GRANT} took with its grant (Story 25.6, {@link #splits}). The world's
 * own canaries are the {@code SM_*} ones ({@code authz/canaries.csv}).
 * <p>
 * {@link MatrixCaller#CROSS_ROLE_SELF} is the same person, called on a normal Job of another Customer and Mentor where
 * they are an {@code ACTIVE} member ({@code job=self-member}).
 * <p>
 * What the schema cannot hold before its story (the spec's part 3): the Dispute is recorded as opened by the Customer,
 * where AD-20 wants a member's (Story 25.7); the handover names the member in the column that still says "mentor"
 * (Story 25.5). The rows that need those are pending.
 */
public class SelfMentoredFixture implements MatrixFixture {

	private static final Instant SIGNED = JobContent.SIGNED;

	private static final Instant WINDOW_END = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(5));

	private static final Instant WRITTEN = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1));

	private static final Instant ACCEPTED = SIGNED.plus(Duration.ofDays(3));

	private static final long RELEASED_PRICE = 2_400_000;

	private static final long HELD_PRICE = 900_000;

	/** The third Milestone's price, accepted, its split held by the open Split Resolution (Story 25.6). */
	private static final long RESOLVED_PRICE = 1_100_000;

	/** The callers this fixture realizes in the mode world beside the Customer who mentors. */
	private static final Set<MatrixCaller> PEOPLE = Set.of(MatrixCaller.CREW_ASSIGNED, MatrixCaller.CREW_ACTIVE,
			MatrixCaller.CREW_FORMER, MatrixCaller.LOCKED, MatrixCaller.CREW_ACTIVE_PARTY, MatrixCaller.CREW_FORMER_PARTY,
			MatrixCaller.ADMIN_DISPUTE_GRANT, MatrixCaller.ADMIN_TEAM_ISSUE_GRANT,
			MatrixCaller.ADMIN_SPLIT_RESOLUTION_GRANT);

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private final List<AdminReadGrants> grants;

	private final StoredFiles files;

	private final PlatformTransactionManager transactionManager;

	private final ApplicationContext context;

	private final Canaries canaries = Canaries.load();

	private UUID customer;

	private UUID assigned;

	private UUID active;

	private UUID former;

	private UUID locked;

	private UUID activeParty;

	private UUID formerParty;

	private UUID canaryMember;

	private UUID disputeAdmin;

	private UUID teamIssueAdmin;

	private UUID resolutionAdmin;

	public SelfMentoredFixture(JdbcClient jdbc, SessionRepository<? extends Session> sessions, List<AdminReadGrants> grants,
			StoredFiles files, PlatformTransactionManager transactionManager, ApplicationContext context) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.grants = List.copyOf(grants);
		this.files = files;
		this.transactionManager = transactionManager;
		this.context = context;
	}

	@Override
	public void seed(MatrixWorld world) {
		if (!world.selfMode()) {
			return;
		}
		this.customer = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		JobContent.customerProfile(this.jdbc, this.customer, "Khách hàng tự hướng dẫn", value("SM_CONTACT_EMAIL"));
		put(world, "customerId", this.customer);
		put(world, "username", TestPeople.login(this.jdbc, this.customer));
		UUID product = JobContent.product(this.jdbc, this.customer, "Sản phẩm tự hướng dẫn");
		put(world, "productId", product);
		UUID job = job(product, "Việc tự hướng dẫn ma trận", "ACTIVE");
		put(world, "jobId", job);
		// The /api/v1/boards/{boardId}/... endpoints name the Job itself, and "Tìm việc" lists it while it recruits.
		put(world, "boardId", job);
		put(world, "postId", job);
		crew(world, job, product);
		repositories(job, product);
		UUID chat = JobContent.chat(this.jdbc, job, product, "CREW");
		put(world, "crewChatId", chat);
		JobContent.message(this.jdbc, chat, this.customer, "Chào cả nhóm, mình bắt đầu nhé", SIGNED.plus(Duration.ofHours(1)));
		put(world, "messageId", JobContent.message(this.jdbc, chat, this.customer, value("SM_CREW_CHAT"), WRITTEN));
		UUID chatFile = storedFile("chat.attachment", this.customer, "yeu-cau.pdf");
		JobContent.attachmentMessage(this.jdbc, chat, this.customer, chatFile, "yeu-cau.pdf", WRITTEN.plus(Duration.ofMinutes(10)));
		put(world, "chatFileId", chatFile);
		UUID held = agreementAndMilestones(world, job, product);
		put(world, "disputeId", dispute(job, product, held));
		ratingsAndIssues(world, job, product);
		// The Customer's open due-date Change Request (FR-34), which the members answer from Story 25.7 on.
		UUID changeRequest = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.change_request (id, job_id, proposer, proposer_account_id, description, items, status, created_at)
				VALUES (:id, :job, 'CUSTOMER', :customer, 'Cần thêm thời gian', CAST(:items AS JSONB), 'OPEN', :at)
				""")
			.param("id", changeRequest)
			.param("job", job)
			.param("customer", this.customer)
			.param("items", "[{\"milestoneId\":\"" + held + "\",\"number\":2,\"title\":\"Sửa lỗi trùng giờ\","
					+ "\"oldDueOn\":\"2026-11-20\",\"newDueOn\":\"2027-01-20\"}]")
			.param("at", Timestamp.from(WRITTEN))
			.update();
		put(world, "changeRequestId", changeRequest);
		this.resolutionAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		enrol(this.resolutionAdmin, "phan-chia-tu-huong-dan");
		splits(world, job, product);
		memberJob(world);
		// The Job Post's screenshot, which the Customer added on the Draft: public while the Job is listed.
		UUID screenshot = storedFile("job.screenshot", this.customer, "man-hinh-loi.pdf");
		this.jdbc.sql("""
				INSERT INTO job.job_screenshot (job_id, file_id, file_name, size_bytes, added_at)
				VALUES (:job, :file, 'man-hinh-loi.pdf', 2048, :at)
				""").param("job", job).param("file", screenshot).param("at", Timestamp.from(SIGNED)).update();
		put(world, "fileId", screenshot);
		put(world, "screenshotId", screenshot);
		// Nobody bids on a Self-mentored Job, no Abuse Report names its content, and its splits have no Mentor party
		// (Story 25.6): the endpoints that name one of these answer for an object that does not exist.
		for (String absent : List.of("applicationId", "proofFileId", "proofUploadId", "reportId", "mentorPartyId",
				"resolutionMentorPartyId")) {
			put(world, absent, UUID.randomUUID());
		}
		put(world, "resolutionStepUpCode", "000000");
		// Story 25.4 (FR-40, B-07): the Customer's second Job, on a Product of its own, in Agreement with its stall passed
		// and nobody recruited yet, which they cancel alone ({@code selfJob=stalled}).
		UUID stalled = job(JobContent.product(this.jdbc, this.customer, "Sản phẩm chờ thỏa thuận"),
				"Việc tự hướng dẫn chờ thỏa thuận", "AGREEMENT");
		this.jdbc.sql("UPDATE job.job SET stall_deadline_passed_at = :at WHERE id = :id")
			.param("at", Timestamp.from(WRITTEN))
			.param("id", stalled)
			.update();
		put(world, "stalledBoardId", stalled);
	}

	/**
	 * The mode world's splits (Story 25.6, AD-29, AD-40): none has a Mentor party, and the Customer drafts each and is no
	 * party of any. The released Milestone's ({@code {selfSplitId}}, with the parties {@code {selfActivePartyId}} and
	 * {@code {selfFormerPartyId}}) is out for confirmation at 57/43, the active party's line being {@code SM_SPLIT_PART}. A
	 * third Milestone, accepted with its pool, has its split held by the former party's open Split Resolution
	 * ({@code {selfResolutionId}} on {@code {selfResolutionSplitId}}, with the {@code SM_SPLIT_RESOLUTION_REASON}), which
	 * {@link MatrixCaller#ADMIN_SPLIT_RESOLUTION_GRANT} took with its grant.
	 */
	private void splits(MatrixWorld world, UUID job, UUID product) {
		if (RELEASED_PRICE * 57 / 100 != Long.parseLong(value("SM_SPLIT_PART"))) {
			throw new IllegalStateException("The active party's line is the SM_SPLIT_PART canary");
		}
		UUID released = this.jdbc.sql("SELECT id FROM job.milestone WHERE job_id = :job AND position = 0")
			.param("job", job)
			.query(UUID.class)
			.single();
		Instant due = MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(3));
		UUID[] proposed = JobContent.selfProposedSplit(this.jdbc, job, product, released, 1, this.customer, this.activeParty,
				this.formerParty, 57, 43, "PROPOSED", SIGNED.plus(Duration.ofDays(1)), ACCEPTED, due);
		put(world, "splitId", proposed[0]);
		put(world, "activePartyId", proposed[1]);
		put(world, "formerPartyId", proposed[2]);
		UUID third = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, submitted_at, handover_id, accepted_at)
				VALUES (:id, :job, 2, 'Giao diện đặt bàn', :price, DATE '2026-11-05', 2, :signed, 'ACCEPTED', :funded, :submitted,
				    :handover, :accepted)
				""").param("id", third).param("job", job).param("price", RESOLVED_PRICE).param("signed", Timestamp.from(SIGNED))
			.param("funded", Timestamp.from(SIGNED.plus(Duration.ofDays(1))))
			.param("submitted", Timestamp.from(ACCEPTED.minus(Duration.ofDays(1)))).param("handover", UUID.randomUUID())
			.param("accepted", Timestamp.from(ACCEPTED)).update();
		JobContent.fund(this.jdbc, job, product, third, this.customer, RESOLVED_PRICE, SIGNED.plus(Duration.ofDays(1)));
		JobContent.pool(this.jdbc, job, product, third, RESOLVED_PRICE, ACCEPTED);
		UUID[] held = JobContent.selfProposedSplit(this.jdbc, job, product, third, 3, this.customer, this.activeParty,
				this.formerParty, 50, 50, "IN_RESOLUTION", SIGNED.plus(Duration.ofDays(1)), ACCEPTED, due);
		UUID resolution = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO dispute.split_resolution (id, split_id, job_id, product_id, milestone_id, milestone_number, raiser_party_id,
				    raiser_account_id, split_version_no, reason, status, raised_at, undecided_due_at)
				VALUES (:id, :split, :job, :product, :milestone, 3, :party, :raiser, 1, :reason, 'OPEN', :raised, :due)
				""")
			.param("id", resolution)
			.param("split", held[0])
			.param("job", job)
			.param("product", product)
			.param("milestone", third)
			.param("party", held[2])
			.param("raiser", this.formerParty)
			.param("reason", value("SM_SPLIT_RESOLUTION_REASON"))
			.param("raised", Timestamp.from(WRITTEN))
			.param("due", Timestamp.from(WRITTEN.plus(Duration.ofDays(7))))
			.update();
		grant(this.resolutionAdmin, job, AdminReadItem.forSplitResolution(held[0]), resolution, grant -> this.jdbc
			.sql("UPDATE dispute.split_resolution SET assigned_admin_id = :admin, assigned_at = :at, grant_id = :grant WHERE id = :id")
			.param("admin", this.resolutionAdmin)
			.param("at", Timestamp.from(WRITTEN))
			.param("grant", grant)
			.param("id", resolution)
			.update());
		put(world, "resolutionId", resolution);
		put(world, "resolutionSplitId", held[0]);
		put(world, "resolutionActivePartyId", held[1]);
		put(world, "resolutionFormerPartyId", held[2]);
	}

	/**
	 * As the crew Job has them, whatever its state: the Rating Window ended with the Crew's Ratings published two days
	 * ago, the Customer's Rating of the active member ({@code {selfRatingId}}), that member's Team Issue about it
	 * ({@code {selfTeamIssueId}}), which waits for the answer of the Customer who mentors, and a second member's issue,
	 * escalated, which {@link MatrixCaller#ADMIN_TEAM_ISSUE_GRANT} took with its grant.
	 */
	private void ratingsAndIssues(MatrixWorld world, UUID job, UUID product) {
		Instant published = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(2));
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at, crew_published_at)
				VALUES (:job, :product, :customer, :customer, 'Việc tự hướng dẫn ma trận', :opened, :published, :published,
				    :published)
				""").param("job", job).param("product", product).param("customer", this.customer)
			.param("opened", Timestamp.from(published.minus(Duration.ofDays(14))))
			.param("published", Timestamp.from(published))
			.update();
		UUID rating = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, 'MENTOR_TO_STUDENT', :customer, :member, 2, 'Còn chậm', :at)
				""").param("id", rating).param("job", job).param("customer", this.customer).param("member", this.active)
			.param("at", Timestamp.from(published.minus(Duration.ofDays(1))))
			.update();
		put(world, "ratingId", rating);
		Instant raised = MutableClock.DEFAULT_INSTANT;
		UUID issue = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, rating_id, raiser_account_id, mentor_account_id, reason,
				    phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, :rating, :raiser, :customer, 'Điểm chưa đúng', 'ANSWER', 5, 5, :raised, :due)
				""").param("id", issue).param("job", job).param("product", product).param("rating", rating)
			.param("raiser", this.active).param("customer", this.customer).param("raised", Timestamp.from(raised))
			.param("due", Timestamp.from(raised.plus(Duration.ofDays(5))))
			.update();
		put(world, "teamIssueId", issue);
		// The canary member's Team Issue, never escalated: its raiser is no caller, so among the callers its ID is for
		// the one who mentors only.
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, rating_id, raiser_account_id, mentor_account_id, reason,
				    phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, :rating, :raiser, :customer, 'Điểm chưa đúng', 'ANSWER', 5, 5, :raised, :due)
				ON CONFLICT (id) DO NOTHING
				""").param("id", UUID.fromString(value("SM_TEAM_ISSUE_ID"))).param("job", job).param("product", product)
			.param("rating", UUID.randomUUID()).param("raiser", this.canaryMember).param("customer", this.customer)
			.param("raised", Timestamp.from(raised)).param("due", Timestamp.from(raised.plus(Duration.ofDays(5))))
			.update();
		UUID escalated = UUID.randomUUID();
		Instant earlier = raised.minus(Duration.ofDays(1));
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, rating_id, raiser_account_id, mentor_account_id, reason,
				    phase, answer_days, escalation_days, raised_at, answer_due_at, escalation_due_at, answer_kind, answered_at,
				    escalated_at)
				VALUES (:id, :job, :product, :rating, :raiser, :customer, 'Điểm chưa đúng', 'ESCALATED', 5, 5, :raised, :due,
				    :escalationDue, 'KEEP', :answered, :escalatedAt)
				""").param("id", escalated).param("job", job).param("product", product).param("rating", UUID.randomUUID())
			.param("raiser", this.activeParty).param("customer", this.customer).param("raised", Timestamp.from(earlier))
			.param("due", Timestamp.from(earlier.plus(Duration.ofDays(5))))
			.param("escalationDue", Timestamp.from(earlier.plus(Duration.ofDays(10))))
			.param("answered", Timestamp.from(earlier.plus(Duration.ofHours(2))))
			.param("escalatedAt", Timestamp.from(earlier.plus(Duration.ofHours(3))))
			.update();
		put(world, "escalatedTeamIssueId", escalated);
		this.teamIssueAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		// Enrolled with TOTP (ADM-11): its decision's step-up takes the code of the fixed clock's step.
		put(world, "teamIssueStepUpCode", AdministratorTestSupport
			.code(enrol(this.teamIssueAdmin, "van-de-nhom-tu-huong-dan"), MutableClock.DEFAULT_INSTANT));
		grant(this.teamIssueAdmin, job, AdminReadItem.forEscalatedTeamIssue(), escalated, grant -> this.jdbc
			.sql("UPDATE dispute.team_issue SET assigned_admin_id = :admin, assigned_at = :at, grant_id = :grant WHERE id = :id")
			.param("admin", this.teamIssueAdmin)
			.param("at", Timestamp.from(WRITTEN))
			.param("grant", grant)
			.param("id", escalated)
			.update());
	}

	/**
	 * The normal Job of the mode world ({@code {selfMemberJobId}}, AD-20): an Active Job of another Customer and another
	 * Mentor where the Customer who mentors the first Job is an {@code ACTIVE} member, which
	 * {@link MatrixCaller#CROSS_ROLE_SELF} runs on. It has its confirmed Agreement, a Crew Chat, and a Job Chat whose
	 * message is {@code SM_MEMBER_JOB_CHAT}; its Customer's contact email is {@code SM_MEMBER_JOB_CONTACT}. There the
	 * person is a member and nothing more.
	 */
	private void memberJob(MatrixWorld world) {
		UUID owner = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		JobContent.customerProfile(this.jdbc, owner, "Khách hàng của việc thường", value("SM_MEMBER_JOB_CONTACT"));
		UUID mentor = TestPeople.mentor(this.jdbc, "Người hướng dẫn của việc thường", List.of(TestPeople.REACT), "React");
		UUID product = JobContent.product(this.jdbc, owner, "Sản phẩm của việc thường");
		UUID job = JobContent.activeJob(this.jdbc, product, owner, mentor, "Việc thường có khách hàng tự hướng dẫn làm thành viên");
		JobContent.selectedBid(this.jdbc, job, product, owner, mentor, SIGNED);
		UUID milestone = UUID.randomUUID();
		JobContent.confirmedAgreement(this.jdbc, job, owner, mentor, false, milestone, "Giai đoạn 1", 1_500_000, SIGNED);
		JobContent.milestone(this.jdbc, job, milestone, 0, "Giai đoạn 1", 1_500_000, "PLANNED", null, null, null, SIGNED);
		JobContent.crewRow(this.jdbc, job, this.customer, "ACTIVE", null);
		UUID crewChat = JobContent.chat(this.jdbc, job, product, "CREW");
		JobContent.message(this.jdbc, crewChat, mentor, "Chào cả nhóm", WRITTEN);
		UUID jobChat = JobContent.chat(this.jdbc, job, product, "JOB");
		JobContent.message(this.jdbc, jobChat, owner, value("SM_MEMBER_JOB_CHAT"), WRITTEN);
		world.put("selfMemberJobId", job);
	}

	private String value(String canary) {
		return this.canaries.get(canary).value();
	}

	private AdministratorTestSupport.Administrator enrol(UUID admin, String name) {
		return AdministratorTestSupport.enrol(this.context, admin, name + "." + admin + "@vibefix.test",
				"Quản trị " + name, MutableClock.DEFAULT_INSTANT);
	}

	/**
	 * The Job's repositories as J7 left them (D11, AD-11, R69): the Setup done and recording the mode, the active member
	 * with write on the Working Repository, the member of the split still invited to it, and the Customer who mentors with
	 * their read on the Intermediary Repository alone. The Working Repository's name is {@code SM_WORKING_REPOSITORY}; its
	 * page and the page of the pending invitation follow from the name ({@code SM_WORKING_REPOSITORY_URL},
	 * {@code SM_INVITATION_URL}), and none of the three ever reaches the Customer who mentors.
	 */
	private void repositories(UUID job, UUID product) {
		Timestamp at = Timestamp.from(SIGNED);
		// The full name, organization/name, as the page's URL holds it: SM_WORKING_REPOSITORY is the name alone.
		String working = value("SM_WORKING_REPOSITORY_URL").substring("https://github.com/".length());
		String intermediary = working.replace("working", "intermediary");
		long workingId = 7_250_201L;
		long intermediaryId = 7_250_202L;
		this.jdbc.sql("""
				INSERT INTO repo.job_setup (id, job_id, product_id, status, assessment, self_mentored, source_full_name,
				    default_branch, head_sha, head_read_at, approved_sha, confirmed_at, intermediary_github_id,
				    intermediary_name, working_github_id, working_name, checked_at, intermediary_at, working_at, copied_at,
				    started_at, finished_at)
				VALUES (:id, :job, :product, 'DONE', FALSE, TRUE, 'khach-tu-huong-dan/san-pham', 'main', :sha, :at, :sha, :at,
				    :intermediaryId, :intermediary, :workingId, :working, :at, :at, :at, :at, :at, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", product)
			.param("sha", "5a".repeat(20))
			.param("at", at)
			.param("intermediaryId", intermediaryId)
			.param("intermediary", intermediary)
			.param("workingId", workingId)
			.param("working", working)
			.update();
		grant(job, product, this.active, "CREW", workingId, working, "WRITE");
		grant(job, product, this.activeParty, "CREW", workingId, working, "INVITED");
		grant(job, product, this.customer, "CUSTOMER", intermediaryId, intermediary, "ACCEPTED");
	}

	/** One person's grant on one of the Job's repositories, in {@code status}. */
	private void grant(UUID job, UUID product, UUID account, String role, long repositoryId, String repository,
			String status) {
		boolean invited = "INVITED".equals(status);
		Timestamp at = Timestamp.from(SIGNED);
		this.jdbc.sql("""
				INSERT INTO repo.job_grant (id, job_id, product_id, account_id, role, github_login, github_user_id,
				    repository_github_id, repository_name, crew_change, read_only, status, invitation_id, invited_at,
				    expires_at, accepted_at, write_at, created_at)
				SELECT :id, :job, :product, a.id, :role, a.github_login, a.github_user_id, :repositoryId, :repository, FALSE,
				    FALSE, :status, :invitation, :at, :expires, :accepted, :write, :at
				FROM identity.account a WHERE a.id = :account
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("product", product)
			.param("account", account)
			.param("role", role)
			.param("repositoryId", repositoryId)
			.param("repository", repository)
			.param("status", status)
			.param("invitation", invited ? 7_250_301L : null)
			.param("at", at)
			.param("expires", invited ? Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(5))) : null)
			.param("accepted", invited ? null : at)
			.param("write", "WRITE".equals(status) ? at : null)
			.update();
	}

	/** A Self-mentored Job of the Customer in {@code state}, as J11 leaves it and the later transitions move it. */
	private UUID job(UUID product, String title, String state) {
		UUID job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, mentor_account_id, selected_at, self_mentored,
				    self_mentored_notice_version, version, created_at, updated_at)
				VALUES (:id, :product, :customer, :state, :title, 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'], 'UNDER_2M',
				    DATE '2026-12-01', '{}'::jsonb, 14, :customer, :signed, TRUE, '1.0', 5, :signed, :signed)
				""")
			.param("id", job)
			.param("product", product)
			.param("customer", this.customer)
			.param("state", state)
			.param("title", title)
			.param("signed", Timestamp.from(SIGNED))
			.update();
		return job;
	}

	/** The first Job's Crew, one member per AD-41 state, and its recruitment with a pending Crew Application. */
	private void crew(MatrixWorld world, UUID job, UUID product) {
		this.assigned = JobContent.member(this.jdbc, "Thành viên được chọn (tự hướng dẫn)");
		UUID assignedRow = JobContent.crewRow(this.jdbc, job, this.assigned, "ASSIGNED", null);
		UUID pick = crewApplication(job, product, this.assigned, "PENDING");
		this.jdbc.sql("""
				UPDATE matching.crew_application SET status = 'PICKED', crew_row_id = :row, picked_at = :picked,
				    sign_due_at = :due
				WHERE id = :id
				""")
			.param("row", assignedRow)
			.param("picked", Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1))))
			.param("due", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(2))))
			.param("id", pick)
			.update();
		this.jdbc.sql("UPDATE job.crew_row SET crew_application_id = :pick WHERE id = :row")
			.param("pick", pick)
			.param("row", assignedRow)
			.update();
		this.active = JobContent.member(this.jdbc, "Thành viên đang làm (tự hướng dẫn)");
		put(world, "crewRowId", JobContent.crewRow(this.jdbc, job, this.active, "ACTIVE", null));
		this.locked = JobContent.member(this.jdbc, "Thành viên bị khóa (tự hướng dẫn)");
		JobContent.crewRow(this.jdbc, job, this.locked, "LEFT", WINDOW_END);
		this.jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = 'Vi phạm quy tắc' WHERE id = :id")
			.param("at", Timestamp.from(WINDOW_END))
			.param("id", this.locked)
			.update();
		this.former = JobContent.member(this.jdbc, "Thành viên đã rời (tự hướng dẫn)");
		JobContent.crewRow(this.jdbc, job, this.former, "LEFT", WINDOW_END);
		this.activeParty = JobContent.member(this.jdbc, "Thành viên trong phân chia (tự hướng dẫn)");
		JobContent.crewRow(this.jdbc, job, this.activeParty, "ACTIVE", null);
		this.formerParty = JobContent.member(this.jdbc, "Thành viên đã rời trong phân chia (tự hướng dẫn)");
		JobContent.crewRow(this.jdbc, job, this.formerParty, "LEFT", WINDOW_END);
		// A member who is no caller, whose own words are the canaries: the name and the LinkedIn link the Customer who
		// mentors and the active members read, and the email nobody but an Administrator does.
		this.canaryMember = JobContent.canaryMember(this.jdbc, value("SM_CREW_NAME"), value("SM_CREW_EMAIL"),
				value("SM_CREW_LINKEDIN"), null);
		JobContent.crewRow(this.jdbc, job, this.canaryMember, "ACTIVE", null);
		// A member who left with a reason (FR-82): in the mode the Customer reads it, as the one who mentors (B-16).
		UUID leaver = JobContent.member(this.jdbc, "Thành viên đã rời có lý do (tự hướng dẫn)");
		this.jdbc.sql("UPDATE job.crew_row SET left_note = :note WHERE id = :row")
			.param("note", value("SM_CREW_LEFT_NOTE"))
			.param("row", JobContent.crewRow(this.jdbc, job, leaver, "LEFT", WINDOW_END))
			.update();
		// Recruitment stays on for one more place (FR-81), so the Job is on "Tìm việc" and takes Crew Applications.
		this.jdbc.sql("""
				UPDATE job.job SET recruiting = true, recruiting_since = :at, open_slots = 1,
				    recruit_skill_ids = ARRAY[CAST(:react AS UUID)], recruit_skill_names = ARRAY['React'],
				    recruit_note = 'Cần thêm một bạn làm giao diện'
				WHERE id = :job
				""").param("at", Timestamp.from(SIGNED)).param("react", TestPeople.REACT).param("job", job).update();
		UUID applicant = JobContent.member(this.jdbc, "Người xin vào nhóm (tự hướng dẫn)");
		UUID application = crewApplication(job, product, applicant, "PENDING");
		this.jdbc.sql("UPDATE matching.crew_application SET note = :note WHERE id = :id")
			.param("note", value("SM_CREW_APPLICATION_NOTE"))
			.param("id", application)
			.update();
		put(world, "crewApplicationId", application);
		// Its proof file, which the applicant and the Customer who mentors read, and an upload no application took yet.
		put(world, "crewProofFileId", crewProof(application, job, product, applicant, 1));
		put(world, "crewProofUploadId", crewProof(null, job, product, applicant, null));
	}

	private UUID crewProof(UUID application, UUID job, UUID product, UUID applicant, Integer position) {
		UUID file = storedFile("matching.crew_application_proof", applicant, "bang-chung.pdf");
		this.jdbc.sql("""
				INSERT INTO matching.crew_application_proof (id, crew_application_id, job_id, product_id,
				    applicant_account_id, position, kind, file_id, file_name, size_bytes, caption, added_at)
				VALUES (:id, :application, :job, :product, :applicant, :position, 'FILE', :file, 'bang-chung.pdf', 100,
				    :caption, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("application", application)
			.param("job", job)
			.param("product", product)
			.param("applicant", applicant)
			.param("position", position)
			.param("caption", application == null ? null : value("SM_CREW_APPLICATION_PROOF_CAPTION"))
			.param("file", file)
			.param("at", Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(2))))
			.update();
		return file;
	}

	/** A Crew Application to the Customer who mentors the Job, in {@code status}. */
	private UUID crewApplication(UUID job, UUID product, UUID applicant, String status) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO matching.crew_application (id, job_id, product_id, customer_account_id, mentor_account_id,
				    applicant_account_id, status, note, public_code_notice_version, submitted_at, lapses_at)
				VALUES (:id, :job, :product, :customer, :customer, :applicant, :status, 'Mình muốn vào nhóm', '1.0', :submitted,
				    :lapses)
				""")
			.param("id", id)
			.param("job", job)
			.param("product", product)
			.param("customer", this.customer)
			.param("applicant", applicant)
			.param("status", status)
			.param("submitted", Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(2))))
			.param("lapses", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(12))))
			.update();
		return id;
	}

	/**
	 * The confirmed Agreement, which the Customer confirmed alone, with its External Access item and two Milestones: the
	 * first accepted and pooled, the second ({@code {selfMilestoneId}}) Funded and handed over by the active member
	 * ({@code {selfHandoverId}}). Returns the second.
	 */
	private UUID agreementAndMilestones(MatrixWorld world, UUID job, UUID product) {
		UUID released = UUID.randomUUID();
		UUID held = UUID.randomUUID();
		UUID criterion = UUID.randomUUID();
		UUID access = UUID.randomUUID();
		Timestamp signed = Timestamp.from(SIGNED);
		String content = """
				{"scope":"Sửa lỗi","priceVnd":%d,"codeJob":true,"assessment":false,"skills":[],
				 "externalAccess":[{"id":"%s","system":"Bảng quản trị Supabase","purpose":"Xem lỗi đặt bàn",
				   "environment":"NON_PRODUCTION","accessLevel":"READ"}],
				 "milestones":[{"id":"%s","title":"Thiết lập đặt bàn","priceVnd":%d,"dueOn":"2026-11-01","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Khách đặt được bàn","checkSteps":"1. Mở. 2. Đặt."}]},
				  {"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":%d,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn","checkSteps":"1. Mở. 2. Đặt."}]}]}
				""".formatted(RELEASED_PRICE + HELD_PRICE, access, released, RELEASED_PRICE, UUID.randomUUID(), held, HELD_PRICE,
				criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :customer, :signed)
				""").param("job", job).param("content", content).param("customer", this.customer).param("signed", signed).update();
		this.jdbc.sql("""
				INSERT INTO job.agreement_draft (job_id, scope, price_vnd, revision, saved_at)
				VALUES (:job, 'Sửa lỗi', :price, 1, :signed)
				""").param("job", job).param("price", RELEASED_PRICE + HELD_PRICE).param("signed", signed).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :customer, :signed, :customer, :signed)
				""").param("job", job).param("content", content).param("customer", this.customer).param("signed", signed).update();
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :job").param("job", job).update();
		UUID releasedHandover = UUID.randomUUID();
		UUID handover = UUID.randomUUID();
		JobContent.milestone(this.jdbc, job, released, 0, "Thiết lập đặt bàn", RELEASED_PRICE, "ACCEPTED",
				SIGNED.plus(Duration.ofDays(1)), ACCEPTED, releasedHandover, SIGNED);
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, submitted_at, handover_id)
				VALUES (:id, :job, 1, 'Sửa lỗi trùng giờ', :price, DATE '2026-11-20', 2, :signed, 'SUBMITTED', :funded,
				    :submitted, :handover)
				""").param("id", held).param("job", job).param("price", HELD_PRICE).param("signed", signed)
			.param("funded", Timestamp.from(ACCEPTED)).param("submitted", Timestamp.from(WRITTEN)).param("handover", handover)
			.update();
		JobContent.fund(this.jdbc, job, product, released, this.customer, RELEASED_PRICE, SIGNED.plus(Duration.ofDays(1)));
		put(world, "paymentOrderId", JobContent.fund(this.jdbc, job, product, held, this.customer, HELD_PRICE, ACCEPTED));
		// The first Milestone's release waits in its pool: a split with no Mentor's part is Story 25.6's.
		JobContent.pool(this.jdbc, job, product, released, RELEASED_PRICE, ACCEPTED);
		put(world, "milestoneId", held);
		put(world, "crewMilestoneId", held);
		put(world, "crewCriterionId", criterion);
		this.jdbc.sql("""
				INSERT INTO job.external_access (id, job_id, position, system, purpose, created_at)
				VALUES (:id, :job, 0, 'Bảng quản trị Supabase', 'Xem lỗi đặt bàn', :at)
				""").param("id", access).param("job", job).param("at", signed).update();
		put(world, "accessId", access);
		handover(releasedHandover, job, product, released, 1, 1, "Thiết lập đặt bàn", "ACCEPTED", 3,
				ACCEPTED.minus(Duration.ofDays(1)), ACCEPTED);
		// The held Milestone's first handover was returned; the one waiting for a decision is its second, made after.
		returnedHandover(world, job, product, held);
		handover(handover, job, product, held, 2, 2, "Sửa lỗi trùng giờ", "SUBMITTED", 5, WRITTEN, null);
		this.jdbc.sql("""
				INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
				VALUES (:id, :criterion, 1, 'Hai khách không đặt trùng bàn', '1. Mở. 2. Đặt.', true, 'Đã kiểm tra trên điện thoại')
				""").param("id", handover).param("criterion", criterion).update();
		put(world, "handoverId", handover);
		UUID screenshot = storedFile("delivery.handover_screenshot", this.active, "man-hinh-da-sua.pdf");
		this.jdbc.sql("""
				INSERT INTO delivery.handover_screenshot (file_id, job_id, product_id, account_id, file_name, size_bytes,
				    handover_id, position, uploaded_at)
				VALUES (:file, :job, :product, :member, 'man-hinh-da-sua.pdf', 2048, :handover, 0, :at)
				""").param("file", screenshot).param("job", job).param("product", product).param("member", this.active)
			.param("handover", handover).param("at", Timestamp.from(WRITTEN)).update();
		put(world, "handoverScreenshotFileId", screenshot);
		// The active member's screenshot upload for the next handover (file=upload).
		UUID upload = storedFile("delivery.handover_screenshot", this.active, "man-hinh-ban-giao.pdf");
		this.jdbc.sql("""
				INSERT INTO delivery.handover_screenshot (file_id, job_id, product_id, account_id, file_name, size_bytes,
				    uploaded_at)
				VALUES (:file, :job, :product, :member, 'man-hinh-ban-giao.pdf', 2048, :at)
				""").param("file", upload).param("job", job).param("product", product).param("member", this.active)
			.param("at", Timestamp.from(WRITTEN)).update();
		put(world, "handoverUploadId", upload);
		return held;
	}

	/**
	 * The held Milestone's first handover, which the Customer returned with a reason (FR-86, AD-20's world, Story 25.5):
	 * void, counted on the Milestone, and read through {@code selfHandover=returned}. The handover waiting for a
	 * decision is the Milestone's second.
	 */
	private void returnedHandover(MatrixWorld world, UUID job, UUID product, UUID milestone) {
		UUID returned = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, voided_at, returned,
				    returned_by_account_id, return_reason)
				VALUES (:id, :job, :product, :milestone, 2, 'Sửa lỗi trùng giờ', 1, :member, 'VOIDED', 4, :transition, :at, :at,
				    :voided, TRUE, :customer, 'Bấm bàn giao sớm, chưa sửa xong lỗi trùng giờ')
				""")
			.param("id", returned)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("member", this.active)
			.param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(WRITTEN.minus(Duration.ofDays(1))))
			.param("voided", Timestamp.from(WRITTEN.minus(Duration.ofHours(12))))
			.param("customer", this.customer)
			.update();
		this.jdbc.sql("UPDATE job.milestone SET handover_returns_used = 1 WHERE id = :id").param("id", milestone).update();
		put(world, "returnedHandoverId", returned);
	}

	/** A Milestone Handover the active member made, whom the column that still says "mentor" names (Story 25.5). */
	private void handover(UUID id, UUID job, UUID product, UUID milestone, int number, int round, String title, String status,
			int jobVersion, Instant at, Instant decided) {
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by)
				VALUES (:id, :job, :product, :milestone, :number, :title, :round, :member, :status, :version, :transition, :at,
				    :at, :decided, :decider)
				""")
			.param("id", id)
			.param("round", round)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("number", number)
			.param("title", title)
			.param("member", this.active)
			.param("status", status)
			.param("version", jobVersion)
			.param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(at))
			.param("decided", decided == null ? null : Timestamp.from(decided))
			.param("decider", decided == null ? null : this.customer)
			.update();
	}

	/**
	 * The open Dispute about the held Milestone, which froze the Job as {@code Disputes.open} does and which
	 * {@link #disputeAdmin} took with its grant. It is recorded as the Customer's until Story 25.7 lets a member open one.
	 */
	private UUID dispute(UUID job, UUID product, UUID milestone) {
		UUID dispute = UUID.randomUUID();
		this.disputeAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		enrol(this.disputeAdmin, "tranh-chap-tu-huong-dan");
		Instant opened = WRITTEN.plus(Duration.ofHours(2));
		Timestamp at = Timestamp.from(opened);
		grant(this.disputeAdmin, job, AdminReadItem.forDispute(), dispute, grant -> this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at, response_days, statement_due_at, assigned_admin_id, assigned_at, grant_id)
				VALUES (:id, :job, :product, 'CUSTOMER', :customer, 'Giai đoạn chưa đạt', 'HELD_MILESTONE', :milestone, 'ACTIVE',
				    'OPEN', :at, 5, :due, :admin, :at, :grant)
				""").param("id", dispute).param("job", job).param("product", product).param("customer", this.customer)
			.param("milestone", milestone).param("at", at).param("due", Timestamp.from(opened.plus(Duration.ofDays(5))))
			.param("admin", this.disputeAdmin).param("grant", grant).update());
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :job").param("at", at).param("job", job).update();
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", job)
			.param("at", at)
			.update();
		return dispute;
	}

	private void grant(UUID admin, UUID job, Set<AdminReadItem> items, UUID reason, Consumer<UUID> record) {
		UUID grant = UUID.randomUUID();
		record.accept(grant);
		this.grants.forEach(module -> module.grantAdminRead(grant, admin, job, items, reason));
	}

	private UUID storedFile(String purpose, UUID author, String fileName) {
		PreparedFile prepared = this.files.prepare(new FileUpload(purpose, author, fileName, TestFiles.pdf(),
				Set.of(FileKind.PDF)));
		return Objects.requireNonNull(new TransactionTemplate(this.transactionManager)
			.execute(status -> this.files.store(prepared).id()));
	}

	/** Seeds {@code value} under the mode world's name of {@code name}. */
	private static void put(MatrixWorld world, String name, Object value) {
		world.put(MatrixRow.selfName(name), value);
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		if (!world.selfMode()) {
			return Optional.empty();
		}
		if (caller == MatrixCaller.CUSTOMER_MENTOR || caller == MatrixCaller.CROSS_ROLE_SELF) {
			return signedIn(this.customer, Role.CUSTOMER);
		}
		if (!PEOPLE.contains(caller)) {
			return Optional.empty();
		}
		return switch (caller) {
			case CREW_ASSIGNED -> signedIn(this.assigned, Role.STUDENT);
			case CREW_ACTIVE -> signedIn(this.active, Role.STUDENT);
			case CREW_FORMER -> signedIn(this.former, Role.STUDENT);
			case LOCKED -> signedIn(this.locked, Role.STUDENT);
			case CREW_ACTIVE_PARTY -> signedIn(this.activeParty, Role.STUDENT);
			case CREW_FORMER_PARTY -> signedIn(this.formerParty, Role.STUDENT);
			case ADMIN_DISPUTE_GRANT -> signedIn(this.disputeAdmin, Role.ADMINISTRATOR);
			case ADMIN_TEAM_ISSUE_GRANT -> signedIn(this.teamIssueAdmin, Role.ADMINISTRATOR);
			case ADMIN_SPLIT_RESOLUTION_GRANT -> signedIn(this.resolutionAdmin, Role.ADMINISTRATOR);
			default -> Optional.empty();
		};
	}

	private Optional<RequestPostProcessor> signedIn(UUID account, Role role) {
		return Optional.of(IdentityTestSupport.signedIn(this.sessions, account, role, MutableClock.DEFAULT_INSTANT));
	}

}
