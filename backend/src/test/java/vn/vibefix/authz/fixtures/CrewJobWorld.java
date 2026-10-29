package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.files.FileKind;
import vn.vibefix.files.FileUpload;
import vn.vibefix.files.PreparedFile;
import vn.vibefix.files.StoredFiles;
import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.support.MutableClock;
import vn.vibefix.support.TestFiles;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * The crew Job (AD-41, AD-20, Story 20.5): an Active Job of the Customer's Product, built by
 * {@link ProductFixture} so that {@link MatrixCaller#CUSTOMER} is its Customer too, with its Crew and its
 * content. It realizes:
 * <ul>
 * <li>{@link MatrixCaller#MENTOR}, the Mentor of this Job and of the second Job below;</li>
 * <li>one member per Crew state: {@link MatrixCaller#CREW_ASSIGNED}, {@link MatrixCaller#CREW_ACTIVE}, and
 * {@link MatrixCaller#CREW_FORMER}, released {@code LEFT} by a lock and unlocked since; {@link MatrixCaller#LOCKED}
 * is a member the lock released {@code LEFT} who is still locked. Both windows ended before the Job's later
 * content was written;</li>
 * <li>{@link MatrixCaller#CROSS_ROLE}, an {@code ACTIVE} member who is the Customer of a second Active Job, whose
 * Crew member (shown name, email, LinkedIn link), Crew Chat, Work Item note and Page are the {@code CROSS_*}
 * canaries;</li>
 * <li>{@link MatrixCaller#ADMIN_DISPUTE_GRANT}, the Administrator who took this Job's open Dispute
 * ({@code {disputeId}}) with its real grant ({@link AdminReadItem#forDispute()});</li>
 * <li>{@link MatrixCaller#ADMIN_TEAM_ISSUE_GRANT}, the Administrator who took an escalated Team Issue another
 * member raised, with its real grant ({@link AdminReadItem#forEscalatedTeamIssue});</li>
 * <li>{@link MatrixCaller#ADMIN_ABUSE_REPORT_GRANT}, the Administrator assigned to the active member's Abuse
 * Report ({@code {reportId}}) on the {@code CREW_CHAT} canary message ({@code {messageId}}), holding the grant on
 * that one message.</li>
 * </ul>
 * Two more {@code ACTIVE} members are no caller: the canary member, whose shown name, Google email, LinkedIn link,
 * released part ({@code CREW_SHARE}, under Crew Terms version 1) and not escalated Team Issue
 * ({@code TEAM_ISSUE_ID}) are the Crew canaries, and the member who escalated the Team Issue, who hides their
 * student name ({@code STUDENT_HIDDEN_NAME}), owns a Story of their own ({@code {raiserWorkItemId}}, which
 * {@code item=raiser} binds to {@code {workItemId}}), and has a published Portfolio Entry on the Mentor's earlier
 * Job, so their public profile is {@code {memberUsername}}'s, which {@code user=member} binds to {@code {username}}
 * (Story 21.3). The Job Chat holds
 * the {@code JOB_CHAT} canary, which no Crew state reads, and the Mentor holds the contact grant on the Customer's
 * {@code CONTACT_EMAIL} (FR-3).
 * <p>
 * It also seeds the Job's confirmed Agreement, which the Job records as its version, with two Milestones pinned to
 * Crew Terms version 1: the first accepted and released to every party (the ledger's FUND, then its RELEASE
 * entries), the second ({@code {milestoneId}}, {@code {crewMilestoneId}}) Funded by the Customer's paid order
 * ({@code {paymentOrderId}}) and handed over. The escalating member's escalated share Team Issue holds their part
 * of it, as {@code JdbcShareHolds} does, and the Customer's open Dispute about it froze the Job as
 * {@code Disputes.open} does ({@code job.job.frozen_at} and {@code timers.frozen_job}). {@code {crewRowId}} is the
 * active member's Crew row and {@code {accessId}} the Job's External Access item. The Job's Board
 * ({@code {boardId}}) holds the criterion's seeded Story ({@code {workItemId}}), which carries the Mentor's
 * {@code WORK_ITEM_NOTE} note ({@code {workItemNoteId}}) and {@code ATTACHMENT} file ({@code {attachmentId}}, stored
 * as work stores it: {@code {attachmentFileId}}, which {@code file=attachment} binds to {@code {fileId}}), and a
 * team-only Page ({@code {pageId}}, version {@code {versionNo}}) whose body is the {@code CREW_PAGE} canary, all
 * written by the Mentor after the windows ended, and a
 * Milestone Handover ({@code {handoverId}}) whose Work Item note is the {@code WORK_ITEM_NOTE} canary. The Story is
 * the active member's to work on, the Crew Chat holds a second message no Abuse Report names
 * ({@code CREW_CHAT_UNREPORTED}) and a file the Mentor sent after the windows ended ({@code file=chat}), the
 * handover has an attached screenshot ({@code file=screenshot}), and the Mentor has a screenshot upload waiting
 * for the next handover ({@code {handoverUploadId}}, which {@code file=upload} binds to {@code {fileId}}); every file
 * is stored through {@code files}. The Job also holds the active member's share Team Issue ({@code {teamIssueId}})
 * on the held Milestone, waiting for the Mentor's answer: a running Job has no Rating to contest, and the
 * Customer's open due-date Change Request ({@code {changeRequestId}}) without the Mentor's impact. The Mentor's
 * earlier Job of the Product, Closed with its Milestone released to the former member and the hiding member, holds
 * the former member's {@code PRIVATE_FEEDBACK} about the Mentor; the Customer's Assessment Job, whose Report was
 * accepted, holds the Mentor's drafted follow-up ({@code {followUpId}}). Every running Job has its selected bid,
 * its confirmed Agreement and its Crew Terms. The public profiles' {@code {username}} is the Mentor's. A row's
 * {@code job=crew} makes {@code {jobId}} this Job ({@code {crewJobId}}), and {@code teamIssue=escalated} makes
 * {@code {teamIssueId}} the escalated issue ({@code {escalatedTeamIssueId}}).
 */
final class CrewJobWorld {

	private static final Instant SIGNED = JobContent.SIGNED;

	private static final Instant WINDOW_END = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(5));

	private static final Instant WRITTEN = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1));

	/** The first Milestone's price, all of it released: {@code CREW_SHARE} is the canary member's 20 % of it. */
	private static final long RELEASED_BASE = 36_582_145;

	/** The second Milestone's price, Funded and held. */
	private static final long HELD_PRICE = 800_000;

	/** When the first Milestone was Funded, a day after the Crew signed. */
	private static final Instant FUNDED = SIGNED.plus(Duration.ofDays(1));

	/** When the first Milestone was accepted and released, and the second Funded: before the windows ended. */
	private static final Instant ACCEPTED = SIGNED.plus(Duration.ofDays(3));

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private final List<AdminReadGrants> grants;

	private final Canaries canaries = Canaries.load();

	private final StoredFiles files;

	private final PlatformTransactionManager transactionManager;

	private final ApplicationContext context;

	private UUID mentor;

	private UUID assigned;

	private UUID active;

	private UUID locked;

	private UUID former;

	private UUID crossRole;

	private UUID disputeAdmin;

	private UUID teamIssueAdmin;

	private UUID abuseAdmin;

	CrewJobWorld(JdbcClient jdbc, SessionRepository<? extends Session> sessions, List<AdminReadGrants> grants,
			StoredFiles files, PlatformTransactionManager transactionManager, ApplicationContext context) {
		this.context = context;
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.grants = List.copyOf(grants);
		this.files = files;
		this.transactionManager = transactionManager;
	}

	void seed(MatrixWorld world, UUID owner, UUID product) {
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.mentor = TeamTestSupport.mentor(this.jdbc, "Người hướng dẫn nhóm ma trận", List.of(TeamTestSupport.REACT),
				"React");
		UUID job = JobContent.activeJob(this.jdbc, product, owner, this.mentor, "Việc nhóm ma trận");
		// J3 selected the Mentor's bid (FR-15): a running Job's selected bid is a real one.
		JobContent.selectedBid(this.jdbc, job, product, owner, this.mentor, SIGNED);
		// The Mentor reads the Customer's contact from Agreement until the Job ends (FR-3): job pushed the grant.
		this.jdbc.sql("""
				INSERT INTO identity.contact_grant (customer_account_id, mentor_account_id, job_id, granted_at)
				VALUES (:owner, :mentor, :job, :at)
				""").param("owner", owner).param("mentor", this.mentor).param("job", job)
			.param("at", Timestamp.from(SIGNED)).update();
		this.assigned = JobContent.member(this.jdbc, "Sinh viên được giao");
		JobContent.crewRow(this.jdbc, job, this.assigned, "ASSIGNED", null);
		this.active = JobContent.member(this.jdbc, "Sinh viên đang làm");
		UUID activeRow = JobContent.crewRow(this.jdbc, job, this.active, "ACTIVE", null);
		this.locked = JobContent.member(this.jdbc, "Sinh viên bị khóa");
		UUID lockedRow = JobContent.crewRow(this.jdbc, job, this.locked, "LEFT", WINDOW_END);
		// A lock counts as leaving (Story 20.4): the account is locked when its window ended.
		this.jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = 'Vi phạm quy tắc' WHERE id = :id")
			.param("at", Timestamp.from(WINDOW_END))
			.param("id", this.locked)
			.update();
		// Released LEFT by a lock and unlocked since (deferred B16): an unlock brings nothing back.
		this.former = JobContent.member(this.jdbc, "Sinh viên đã rời");
		UUID formerRow = JobContent.crewRow(this.jdbc, job, this.former, "LEFT", WINDOW_END);
		this.crossRole = JobContent.member(this.jdbc, "Sinh viên hai vai trò");
		UUID crossRow = JobContent.crewRow(this.jdbc, job, this.crossRole, "ACTIVE", null);
		UUID canaryMember = JobContent.canaryMember(this.jdbc, value("CREW_NAME"), value("CREW_EMAIL"),
				value("CREW_LINKEDIN"), null);
		UUID canaryRow = JobContent.crewRow(this.jdbc, job, canaryMember, "ACTIVE", null);
		// The member who hides their student name (FR-51, X9): their PUB-4 is {memberUsername}'s (review iteration 4).
		UUID escalating = JobContent.hidingMember(this.jdbc, "Sinh viên nhờ quản trị viên", value("STUDENT_HIDDEN_NAME"));
		UUID escalatingRow = JobContent.crewRow(this.jdbc, job, escalating, "ACTIVE", null);
		UUID chat = JobContent.chat(this.jdbc, job, product, "CREW");
		JobContent.message(this.jdbc, chat, this.mentor, "Chào cả nhóm, mình bắt đầu nhé", SIGNED.plus(Duration.ofHours(1)));
		UUID canaryMessage = JobContent.message(this.jdbc, chat, this.mentor, value("CREW_CHAT"), WRITTEN);
		world.put("messageId", canaryMessage);
		// A message no Abuse Report names (review iteration 3): the reported message's grant never reads it.
		JobContent.message(this.jdbc, chat, this.mentor, value("CREW_CHAT_UNREPORTED"), WRITTEN.plus(Duration.ofMinutes(5)));
		// A file the Mentor sent in the Crew Chat after the windows ended (file=chat, G7).
		UUID chatFile = storedFile("chat.attachment", this.mentor, "bien-ban-nhom.pdf");
		JobContent.attachmentMessage(this.jdbc, chat, this.mentor, chatFile, "bien-ban-nhom.pdf",
				WRITTEN.plus(Duration.ofMinutes(10)));
		world.put("chatFileId", chatFile);
		world.put("crewChatId", chat);
		UUID jobChat = JobContent.chat(this.jdbc, job, product, "JOB");
		JobContent.message(this.jdbc, jobChat, owner, value("JOB_CHAT"), WRITTEN);
		// Two Milestones (FR-30, AD-41), both pinned to Crew Terms version 1: the first accepted and released, the
		// second Funded and handed over, which the escalated share Team Issue holds and the open Dispute concerns.
		UUID released = UUID.randomUUID();
		UUID milestone = UUID.randomUUID();
		UUID criterion = UUID.randomUUID();
		// The confirmed Agreement lists the Job's one External Access item (FR-66), which its rows name.
		UUID access = UUID.randomUUID();
		String content = """
				{"scope":"Sửa lỗi","priceVnd":%d,"codeJob":true,"assessment":false,"skills":[],
				 "externalAccess":[{"id":"%s","system":"Bảng quản trị Supabase","purpose":"Xem lỗi đặt bàn",
				   "environment":"NON_PRODUCTION","accessLevel":"READ"}],
				 "milestones":[{"id":"%s","title":"Thiết lập đặt bàn","priceVnd":%d,"dueOn":"2026-11-01","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Khách đặt được bàn","checkSteps":"1. Mở. 2. Đặt."}]},
				  {"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":%d,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn","checkSteps":"1. Mở. 2. Đặt."}]}]}
				""".formatted(RELEASED_BASE + HELD_PRICE, access, released, RELEASED_BASE, UUID.randomUUID(), milestone,
				HELD_PRICE, criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :signed)
				""").param("job", job).param("content", content).param("mentor", this.mentor)
			.param("signed", Timestamp.from(SIGNED)).update();
		// The live draft the Agreement started with at J4, which stays the Mentor's to read (FR-16).
		this.jdbc.sql("""
				INSERT INTO job.agreement_draft (job_id, scope, price_vnd, revision, saved_at)
				VALUES (:job, 'Sửa lỗi', :price, 1, :signed)
				""").param("job", job).param("price", RELEASED_BASE + HELD_PRICE).param("signed", Timestamp.from(SIGNED))
			.update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :signed, :mentor, :signed)
				""").param("job", job).param("content", content).param("owner", owner).param("mentor", this.mentor)
			.param("signed", Timestamp.from(SIGNED)).update();
		// The Job records its confirmed version, as J5 does: its funding, and the Mentor's balance with the Crew's
		// parts of the release (CREW_SHARE), read through it (review iteration 1: no canary no response can hold).
		this.jdbc.sql("UPDATE job.job SET agreement_version_no = 1 WHERE id = :job").param("job", job).update();
		JobContent.crewTerms(this.jdbc, job, this.mentor, SIGNED);
		JobContent.share(this.jdbc, job, this.mentor, null, 30);
		JobContent.share(this.jdbc, job, this.active, activeRow, 10);
		JobContent.share(this.jdbc, job, this.crossRole, crossRow, 10);
		JobContent.share(this.jdbc, job, canaryMember, canaryRow, 20);
		JobContent.share(this.jdbc, job, escalating, escalatingRow, 10);
		JobContent.share(this.jdbc, job, this.former, formerRow, 10);
		JobContent.share(this.jdbc, job, this.locked, lockedRow, 10);
		UUID handover = UUID.randomUUID();
		UUID releasedHandover = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, submitted_at, handover_id, accepted_at)
				VALUES (:id, :job, 0, 'Thiết lập đặt bàn', :price, DATE '2026-11-01', 2, :signed, 'ACCEPTED', :funded, 1,
				    :submitted, :handover, :accepted)
				""").param("id", released).param("job", job).param("price", RELEASED_BASE)
			.param("signed", Timestamp.from(SIGNED)).param("funded", Timestamp.from(FUNDED))
			.param("submitted", Timestamp.from(ACCEPTED.minus(Duration.ofDays(1)))).param("handover", releasedHandover)
			.param("accepted", Timestamp.from(ACCEPTED)).update();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, submitted_at, handover_id)
				VALUES (:id, :job, 1, 'Sửa lỗi trùng giờ', :price, DATE '2026-11-20', 2, :signed, 'SUBMITTED', :funded, 1,
				    :submitted, :handover)
				""").param("id", milestone).param("job", job).param("price", HELD_PRICE)
			.param("signed", Timestamp.from(SIGNED)).param("funded", Timestamp.from(ACCEPTED))
			.param("submitted", Timestamp.from(WRITTEN)).param("handover", handover).update();
		// The ledger as M1 and M5 leave it (AD-15): each Milestone's FUND, then the first one's release to every party.
		JobContent.fund(this.jdbc, job, product, released, owner, RELEASED_BASE, FUNDED);
		UUID heldOrder = JobContent.fund(this.jdbc, job, product, milestone, owner, HELD_PRICE, ACCEPTED);
		release(job, product, released, canaryMember, escalating);
		world.put("milestoneId", milestone);
		world.put("paymentOrderId", heldOrder);
		world.put("crewRowId", activeRow);
		this.jdbc.sql("""
				INSERT INTO job.external_access (id, job_id, position, system, purpose, created_at)
				VALUES (:id, :job, 0, 'Bảng quản trị Supabase', 'Xem lỗi đặt bàn', :at)
				""").param("id", access).param("job", job).param("at", Timestamp.from(SIGNED)).update();
		world.put("accessId", access);
		JobContent.board(this.jdbc, job, product, "VNM", 2);
		// The active member's Story, written after the windows ended: its title is the WORK_ITEM_TITLE canary.
		UUID item = JobContent.workItem(this.jdbc, job, 1, value("WORK_ITEM_TITLE"), milestone, criterion,
				MutableClock.DEFAULT_INSTANT);
		// The Mentor assigned the Story to the active member (FR-23), who moves and submits it.
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :member WHERE id = :id")
			.param("member", this.active)
			.param("id", item)
			.update();
		// An edit keeps the assignee, which only the Mentor changes (FR-23).
		world.put("workItemAssigneeId", this.active);
		world.put("boardId", job);
		// A row's job=crew binds {jobId} to it (MatrixRow.Binding.CREW_JOB).
		world.put("crewJobId", job);
		world.put("crewMilestoneId", milestone);
		UUID teamIssue = shareIssue(UUID.randomUUID(), job, product, this.active, milestone);
		world.put("teamIssueId", teamIssue);
		// Another member's Team Issue, never escalated: its ID is for the Mentor only. A suite that seeds several
		// worlds on one database keeps the first, since the canary is one fixed ID.
		shareIssue(UUID.fromString(value("TEAM_ISSUE_ID")), job, product, canaryMember, milestone);
		world.put("workItemId", item);
		// The escalated issue's raiser's own Story, which that issue's grant reads (forEscalatedTeamIssue, G8).
		UUID raiserItem = JobContent.workItem(this.jdbc, job, 2, "Ghi phần việc đã làm", milestone, null,
				MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("UPDATE work.work_item SET assignee_account_id = :member WHERE id = :id")
			.param("member", escalating)
			.param("id", raiserItem)
			.update();
		world.put("raiserWorkItemId", raiserItem);
		// The Attachment's file, stored as work stores it, so AttachmentFiles finds it (file=attachment, G7).
		UUID attachmentFile = storedFile("work.attachment", this.mentor, value("ATTACHMENT"));
		world.put("attachmentFileId", attachmentFile);
		UUID attachment = JobContent.attachment(this.jdbc, item, job, this.mentor, value("ATTACHMENT"), attachmentFile,
				WRITTEN);
		world.put("workItemNoteId", JobContent.note(this.jdbc, item, job, this.mentor, value("WORK_ITEM_NOTE"), WRITTEN));
		world.put("attachmentId", attachment);
		Timestamp written = Timestamp.from(WRITTEN);
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by)
				VALUES (:id, :job, :product, :milestone, 1, 'Thiết lập đặt bàn', 1, :mentor, 'ACCEPTED', 3, :transition, :at, :at,
				    :decided, :owner)
				""")
			.param("id", releasedHandover)
			.param("job", job)
			.param("product", product)
			.param("milestone", released)
			.param("mentor", this.mentor)
			.param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(ACCEPTED.minus(Duration.ofDays(1))))
			.param("decided", Timestamp.from(ACCEPTED))
			.param("owner", owner)
			.update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, 2, 'Sửa lỗi trùng giờ', 1, :mentor, 'SUBMITTED', 5, :transition, :at, :at)
				""")
			.param("id", handover)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("mentor", this.mentor)
			.param("transition", UUID.randomUUID())
			.param("at", written)
			.update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_criterion (handover_id, criterion_id, number, statement, check_steps, met, note)
				VALUES (:id, :criterion, 1, 'Hai khách không đặt trùng bàn', '1. Mở. 2. Đặt.', true, 'Đã kiểm tra trên điện thoại')
				""").param("id", handover).param("criterion", criterion).update();
		this.jdbc.sql("""
				INSERT INTO delivery.handover_item_note (handover_id, work_item_id, item_key, title, status, note)
				VALUES (:id, :item, 'VNM-1', :title, 'TODO', :note)
				""").param("id", handover).param("item", item).param("title", value("WORK_ITEM_TITLE"))
			.param("note", value("WORK_ITEM_NOTE"))
			.update();
		world.put("handoverId", handover);
		// The handover's screenshot, attached at "Gửi bàn giao" (file=screenshot, G7).
		UUID screenshotFile = storedFile("delivery.handover_screenshot", this.mentor, "man-hinh-da-sua.pdf");
		this.jdbc.sql("""
				INSERT INTO delivery.handover_screenshot (file_id, job_id, product_id, account_id, file_name, size_bytes,
				    handover_id, position, uploaded_at)
				VALUES (:file, :job, :product, :mentor, 'man-hinh-da-sua.pdf', 2048, :handover, 0, :at)
				""")
			.param("file", screenshotFile)
			.param("job", job)
			.param("product", product)
			.param("mentor", this.mentor)
			.param("handover", handover)
			.param("at", written)
			.update();
		world.put("handoverScreenshotFileId", screenshotFile);
		// The Mentor's screenshot upload for the next handover (Story 8.3): a row's file=upload makes {fileId} it.
		UUID upload = storedFile("delivery.handover_screenshot", this.mentor, "man-hinh-ban-giao.pdf");
		this.jdbc.sql("""
				INSERT INTO delivery.handover_screenshot (file_id, job_id, product_id, account_id, file_name, size_bytes,
				    uploaded_at)
				VALUES (:file, :job, :product, :mentor, 'man-hinh-ban-giao.pdf', 2048, :at)
				""")
			.param("file", upload)
			.param("job", job)
			.param("product", product)
			.param("mentor", this.mentor)
			.param("at", written)
			.update();
		world.put("handoverUploadId", upload);
		UUID followUp = assessment(owner);
		world.put("followUpId", followUp);
		UUID changeRequest = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.change_request (id, job_id, proposer, proposer_account_id, description, items, status, created_at)
				VALUES (:id, :job, 'CUSTOMER', :owner, 'Cần thêm thời gian', CAST(:items AS JSONB), 'OPEN', :at)
				""")
			.param("id", changeRequest)
			.param("job", job)
			.param("owner", owner)
			.param("items", "[{\"milestoneId\":\"" + milestone + "\",\"number\":2,\"title\":\"Sửa lỗi trùng giờ\","
					+ "\"oldDueOn\":\"2026-11-20\",\"newDueOn\":\"2027-01-20\"}]")
			.param("at", written)
			.update();
		world.put("changeRequestId", changeRequest);
		world.put("disputeId", dispute(job, product, owner, milestone));
		this.teamIssueAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		// Enrolled with TOTP (ADM-11): its decision's step-up takes the code of the fixed clock's step.
		world.put("teamIssueStepUpCode", AdministratorTestSupport.code(enrol(this.teamIssueAdmin, "van-de-nhom"),
				MutableClock.DEFAULT_INSTANT));
		UUID escalated = shareTeamIssue(job, product, escalating, milestone);
		// A row's teamIssue=escalated binds {teamIssueId} to it (MatrixRow.Binding.ESCALATED_TEAM_ISSUE).
		world.put("escalatedTeamIssueId", escalated);
		grant(this.teamIssueAdmin, job, AdminReadItem.forEscalatedTeamIssue(escalating), escalated, grant -> this.jdbc
			.sql("UPDATE dispute.team_issue SET assigned_admin_id = :admin, assigned_at = :at, grant_id = :grant WHERE id = :id")
			.param("admin", this.teamIssueAdmin)
			.param("at", written)
			.param("grant", grant)
			.param("id", escalated)
			.update());
		UUID report = UUID.randomUUID();
		this.abuseAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		enrol(this.abuseAdmin, "bao-cao");
		grant(this.abuseAdmin, job, AdminReadItem.forAbuseReport(AdminReadScope.CREW_CHAT, canaryMessage), report,
				grant -> this.jdbc.sql("""
						INSERT INTO admin.abuse_report (id, reporter_account_id, kind, target_id, job_id, product_id,
						    author_account_id, label, reason, status, created_at, assigned_admin_id, assigned_at, grant_id)
						VALUES (:id, :reporter, 'CREW_CHAT_MESSAGE', :message, :job, :product, :mentor, 'Việc nhóm ma trận',
						    'Lời lẽ xúc phạm', 'OPEN', :at, :admin, :at, :grant)
						""").param("id", report).param("reporter", this.active).param("message", canaryMessage)
					.param("job", job).param("product", product).param("mentor", this.mentor).param("at", written)
					.param("admin", this.abuseAdmin).param("grant", grant).update());
		world.put("reportId", report);
		world.put("crewCriterionId", criterion);
		world.put("item", "NON_CODE_DATA");
		world.put("pageId", JobContent.page(this.jdbc, job, this.mentor, value("CREW_PAGE_TITLE"), value("CREW_PAGE"),
				WRITTEN));
		// A Page the Customer reads, written after the windows ended, and a team-only one written inside them, so
		// each Page filter is seen to keep something (review iteration 4).
		UUID customerPage = JobContent.page(this.jdbc, job, this.mentor, value("CUSTOMER_PAGE"), value("CUSTOMER_PAGE"),
				WRITTEN);
		this.jdbc.sql("UPDATE work.page SET visibility = 'CUSTOMER' WHERE id = :id").param("id", customerPage).update();
		JobContent.page(this.jdbc, job, this.mentor, value("EARLY_PAGE"), value("EARLY_PAGE"),
				WINDOW_END.minus(Duration.ofDays(2)));
		world.put("versionNo", 1);
		privateFeedback(product, owner, escalating);
		secondJob();
		world.put("username", TeamTestSupport.login(this.jdbc, this.mentor));
		world.put("memberUsername", TeamTestSupport.login(this.jdbc, escalating));
		// The retired Team endpoints refuse every caller (Story 21.7 deletes them), so their IDs name nothing.
		world.put("studentId", canaryMember);
		world.put("teamMentorId", this.mentor);
		world.put("freeStudentId", UUID.randomUUID());
		world.put("joinRequestId", UUID.randomUUID());
		world.put("invitationId", UUID.randomUUID());
		world.put("membershipId", UUID.randomUUID());
	}

	/** The Mentor of this Job and of every fixture Job that has one. */
	UUID mentor() {
		return this.mentor;
	}

	Optional<RequestPostProcessor> realize(MatrixCaller caller) {
		return switch (caller) {
			case MENTOR -> signedIn(this.mentor, Role.MENTOR);
			case CREW_ASSIGNED -> signedIn(this.assigned, Role.STUDENT);
			case CREW_ACTIVE -> signedIn(this.active, Role.STUDENT);
			case CREW_FORMER -> signedIn(this.former, Role.STUDENT);
			case LOCKED -> signedIn(this.locked, Role.STUDENT);
			case CROSS_ROLE -> signedIn(this.crossRole, Role.STUDENT);
			case ADMIN_TEAM_ISSUE_GRANT -> signedIn(this.teamIssueAdmin, Role.ADMINISTRATOR);
			case ADMIN_DISPUTE_GRANT -> signedIn(this.disputeAdmin, Role.ADMINISTRATOR);
			case ADMIN_ABUSE_REPORT_GRANT -> signedIn(this.abuseAdmin, Role.ADMINISTRATOR);
			default -> Optional.empty();
		};
	}

	private Optional<RequestPostProcessor> signedIn(UUID account, Role role) {
		return Optional.of(IdentityTestSupport.signedIn(this.sessions, account, role, MutableClock.DEFAULT_INSTANT));
	}

	private String value(String canary) {
		return this.canaries.get(canary).value();
	}

	/**
	 * The first Milestone's release under Crew Terms version 1 (FR-42, AD-41), to every party: the canary member's
	 * part is {@code CREW_SHARE}, which the Mentor's balance and funding list and no one else's. The Mentor takes
	 * the remainder of the whole-VND split.
	 */
	private void release(UUID job, UUID product, UUID milestone, UUID canaryMember, UUID escalating) {
		UUID transition = UUID.randomUUID();
		long tenth = RELEASED_BASE * 10 / 100;
		long canary = RELEASED_BASE * 20 / 100;
		if (canary != Long.parseLong(value("CREW_SHARE"))) {
			throw new IllegalStateException("The canary member's part is the CREW_SHARE canary, not " + canary);
		}
		long mentorPart = RELEASED_BASE - 5 * tenth - canary;
		JobContent.release(this.jdbc, "RELEASE", transition, job, product, milestone, this.mentor, mentorPart, 30,
				RELEASED_BASE, ACCEPTED);
		JobContent.release(this.jdbc, "RELEASE", transition, job, product, milestone, canaryMember, canary, 20,
				RELEASED_BASE, ACCEPTED);
		for (UUID member : List.of(this.active, this.crossRole, escalating, this.former, this.locked)) {
			JobContent.release(this.jdbc, "RELEASE", transition, job, product, milestone, member, tenth, 10, RELEASED_BASE,
					ACCEPTED);
		}
	}

	/**
	 * The escalating member's share Team Issue on the held Milestone (FR-79, AD-40), answered and escalated: raised
	 * while the Milestone was Funded, it holds the member's and the Mentor's parts as {@code payment.holdShares}
	 * does, until it ends. Returns its ID.
	 */
	private UUID shareTeamIssue(UUID job, UUID product, UUID raiser, UUID milestone) {
		UUID id = UUID.randomUUID();
		Instant raised = ACCEPTED.plus(Duration.ofDays(1));
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number, raiser_account_id,
				    mentor_account_id, reason, phase, answer_days, escalation_days, raised_at, answer_due_at, escalation_due_at,
				    answer_kind, answered_at, escalated_at)
				VALUES (:id, :job, :product, 'SHARE', :milestone, 2, :raiser, :mentor, 'Phần chia chưa đúng', 'ESCALATED', 5, 5,
				    :raised, :due, :escalationDue, 'KEEP', :answered, :escalatedAt)
				""")
			.param("id", id)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("raiser", raiser)
			.param("mentor", this.mentor)
			.param("raised", Timestamp.from(raised))
			.param("due", Timestamp.from(raised.plus(Duration.ofDays(5))))
			.param("escalationDue", Timestamp.from(raised.plus(Duration.ofDays(10))))
			.param("answered", Timestamp.from(raised.plus(Duration.ofDays(2))))
			.param("escalatedAt", Timestamp.from(raised.plus(Duration.ofDays(3))))
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.share_hold (team_issue_id, milestone_id, job_id, product_id, raiser_account_id, held_at)
				VALUES (:issue, :milestone, :job, :product, :raiser, :at)
				""").param("issue", id).param("milestone", milestone).param("job", job).param("product", product)
			.param("raiser", raiser).param("at", Timestamp.from(raised)).update();
		return id;
	}

	/**
	 * A member's share Team Issue on the held Milestone, waiting for the Mentor's answer (FR-79, AD-40): a running Job
	 * has no Rating yet, which only J9's window opens (review iteration 4, G9). Raising it held the shares, as
	 * {@code payment.holdShares} does. Returns its ID.
	 */
	private UUID shareIssue(UUID id, UUID job, UUID product, UUID raiser, UUID milestone) {
		Instant raised = MutableClock.DEFAULT_INSTANT;
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, milestone_id, milestone_number, raiser_account_id,
				    mentor_account_id, reason, phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, 'SHARE', :milestone, 2, :raiser, :mentor, 'Phần chia chưa đúng', 'ANSWER', 5, 5,
				    :raised, :due)
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", id)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("raiser", raiser)
			.param("mentor", this.mentor)
			.param("raised", Timestamp.from(raised))
			.param("due", Timestamp.from(raised.plus(Duration.ofDays(5))))
			.update();
		this.jdbc.sql("""
				INSERT INTO payment.share_hold (team_issue_id, milestone_id, job_id, product_id, raiser_account_id, held_at)
				VALUES (:issue, :milestone, :job, :product, :raiser, :at)
				ON CONFLICT (team_issue_id) DO NOTHING
				""").param("issue", id).param("milestone", milestone).param("job", job).param("product", product)
			.param("raiser", raiser).param("at", Timestamp.from(raised)).update();
		return id;
	}

	/** Enrols the grant Administrator {@code admin} with a password and a TOTP secret, as ADM-11's enrolment does. */
	private Administrator enrol(UUID admin, String name) {
		return AdministratorTestSupport.enrol(this.context, admin, name + "." + admin + "@vibefix.test", "Quản trị " + name,
				MutableClock.DEFAULT_INSTANT);
	}

	/** A PDF {@code author} uploaded for {@code purpose}, stored through {@code files} as the owning module stores it. */
	private UUID storedFile(String purpose, UUID author, String fileName) {
		PreparedFile prepared = this.files.prepare(new FileUpload(purpose, author, fileName, TestFiles.pdf(),
				Set.of(FileKind.PDF)));
		return Objects.requireNonNull(new TransactionTemplate(this.transactionManager)
			.execute(status -> this.files.store(prepared).id()));
	}

	/**
	 * The Customer's open Dispute about the held Milestone, which {@link #disputeAdmin} took: as {@code Disputes.open}
	 * does, it froze the Job ({@code job.job.frozen_at}, and the Job's Deadlines through {@code timers.frozen_job}),
	 * and its grant is pushed to every module (AD-20, AD-28). It ends nothing else: the Customer's open Change
	 * Request stays open beside it, since only M5, M8, J8 or J10 lapse one ({@code ChangeRequests.lapse}).
	 */
	private UUID dispute(UUID job, UUID product, UUID owner, UUID milestone) {
		UUID dispute = UUID.randomUUID();
		this.disputeAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null);
		enrol(this.disputeAdmin, "tranh-chap");
		Instant opened = WRITTEN.plus(Duration.ofHours(2));
		Timestamp at = Timestamp.from(opened);
		grant(this.disputeAdmin, job, AdminReadItem.forDispute(), dispute, grant -> this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, milestone_id,
				    job_state, status, opened_at, response_days, statement_due_at, assigned_admin_id, assigned_at, grant_id)
				VALUES (:id, :job, :product, 'CUSTOMER', :owner, 'Giai đoạn chưa đạt', 'HELD_MILESTONE', :milestone, 'ACTIVE',
				    'OPEN', :at, 5, :due, :admin, :at, :grant)
				""").param("id", dispute).param("job", job).param("product", product).param("owner", owner)
			.param("milestone", milestone).param("at", at).param("due", Timestamp.from(opened.plus(Duration.ofDays(5))))
			.param("admin", this.disputeAdmin).param("grant", grant).update());
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :job").param("at", at).param("job", job).update();
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", job)
			.param("at", at)
			.update();
		return dispute;
	}

	/**
	 * Pushes a read grant down to every module holding Job content, as the case module does when its Administrator
	 * takes the case, after {@code record} writes the case row with the grant's ID.
	 */
	private void grant(UUID admin, UUID job, Set<AdminReadItem> items, UUID reason, Consumer<UUID> record) {
		UUID grant = UUID.randomUUID();
		record.accept(grant);
		this.grants.forEach(module -> module.grantAdminRead(grant, admin, job, items, reason));
	}

	/**
	 * The Mentor's earlier, ended Job, where the former member left private feedback about the Mentor and the member who
	 * hides their student name has a published Portfolio Entry, which their public reputation lists.
	 */
	private void privateFeedback(UUID product, UUID owner, UUID hiding) {
		Instant ended = SIGNED.minus(Duration.ofDays(30));
		UUID earlier = endedJob(product, owner, hiding, ended);
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at, crew_published_at)
				VALUES (:job, :product, :owner, :mentor, 'Việc trước của người hướng dẫn', :opened, :ends, :ends, :ends)
				""").param("job", earlier).param("product", product).param("owner", owner).param("mentor", this.mentor)
			.param("opened", Timestamp.from(ended.minus(Duration.ofDays(14)))).param("ends", Timestamp.from(ended)).update();
		this.jdbc.sql("""
				INSERT INTO reputation.private_feedback (id, job_id, mentor_account_id, student_account_id, stars, comment,
				    created_at, delete_after)
				VALUES (:id, :job, :mentor, :student, 2, :comment, :at, :deleteAfter)
				""").param("id", UUID.randomUUID()).param("job", earlier).param("mentor", this.mentor).param("student", this.former)
			.param("comment", value("PRIVATE_FEEDBACK"))
			.param("at", Timestamp.from(ended.minus(Duration.ofDays(1))))
			.param("deleteAfter", Timestamp.from(ended.plus(Duration.ofDays(730))))
			.update();
		this.jdbc.sql("""
				INSERT INTO reputation.portfolio_entry (id, job_id, product_id, account_id, role, job_title, product_name,
				    job_types, skill_ids, milestones_accepted, work_items_approved, closed_at, published_at, created_at)
				VALUES (:id, :job, :product, :account, 'CREW', 'Việc trước của người hướng dẫn', 'Sản phẩm ma trận',
				    ARRAY['BUG_FIX'], CAST(:skills AS UUID[]), 1, 2, :ended, :ended, :ended)
				""").param("id", UUID.randomUUID()).param("job", earlier).param("product", product).param("account", hiding)
			.param("skills", "{" + TeamTestSupport.REACT + "}")
			.param("ended", Timestamp.from(ended))
			.update();
	}

	/**
	 * The Mentor's earlier Job of the Customer's Product (review iteration 4, G9), Closed {@code ended}: J3 selected the
	 * Mentor's bid, its Agreement's one Milestone was Funded, accepted and released under Crew Terms version 1 to the
	 * Mentor and its two members, the former member and the member who hides their student name, and J9 ended their
	 * rows. Returns its ID.
	 */
	private UUID endedJob(UUID product, UUID owner, UUID hiding, Instant ended) {
		Instant signed = ended.minus(Duration.ofDays(20));
		UUID job = JobContent.job(this.jdbc, product, owner, this.mentor, "Việc trước của người hướng dẫn", signed,
				"CLOSED");
		this.jdbc.sql("UPDATE job.job SET updated_at = :at WHERE id = :id").param("at", Timestamp.from(ended))
			.param("id", job)
			.update();
		JobContent.selectedBid(this.jdbc, job, product, owner, this.mentor, signed);
		UUID milestone = UUID.randomUUID();
		long price = 1_000_000;
		JobContent.confirmedAgreement(this.jdbc, job, owner, this.mentor, false, milestone, "Sửa lỗi cũ", price, signed);
		JobContent.crewTerms(this.jdbc, job, this.mentor, signed);
		UUID formerRow = JobContent.crewRow(this.jdbc, job, this.former, "JOB_ENDED", ended, signed);
		UUID hidingRow = JobContent.crewRow(this.jdbc, job, hiding, "JOB_ENDED", ended, signed);
		JobContent.share(this.jdbc, job, this.mentor, null, 60);
		JobContent.share(this.jdbc, job, this.former, formerRow, 20);
		JobContent.share(this.jdbc, job, hiding, hidingRow, 20);
		Instant funded = signed.plus(Duration.ofDays(1));
		Instant accepted = ended.minus(Duration.ofDays(2));
		UUID handover = UUID.randomUUID();
		JobContent.milestone(this.jdbc, job, milestone, 0, "Sửa lỗi cũ", price, "ACCEPTED", funded, accepted, handover,
				signed);
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by)
				VALUES (:id, :job, :product, :milestone, 1, 'Sửa lỗi cũ', 1, :mentor, 'ACCEPTED', 3, :transition, :at, :at,
				    :decided, :owner)
				""")
			.param("id", handover)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("mentor", this.mentor)
			.param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(accepted.minus(Duration.ofDays(1))))
			.param("decided", Timestamp.from(accepted))
			.param("owner", owner)
			.update();
		JobContent.fund(this.jdbc, job, product, milestone, owner, price, funded);
		UUID transition = UUID.randomUUID();
		JobContent.release(this.jdbc, "RELEASE", transition, job, product, milestone, this.mentor, 600_000, 60, price,
				accepted);
		JobContent.release(this.jdbc, "RELEASE", transition, job, product, milestone, this.former, 200_000, 20, price,
				accepted);
		JobContent.release(this.jdbc, "RELEASE", transition, job, product, milestone, hiding, 200_000, 20, price, accepted);
		return job;
	}

	/**
	 * The Customer's Assessment Job on a Product of theirs, mentored by the Mentor (FR-10, FR-11, review iteration 4,
	 * G9): its Report's Milestone was Funded, accepted (M5, which pinned the Report version) and released to the
	 * Mentor alone, so the Mentor drafts follow-up Jobs citing the Report's recommendations. Returns the drafted
	 * follow-up's ID: a follow-up exists only after an accepted Assessment.
	 */
	private UUID assessment(UUID owner) {
		UUID product = JobContent.product(this.jdbc, owner, "Sản phẩm đánh giá");
		UUID job = JobContent.activeJob(this.jdbc, product, owner, this.mentor, "Đánh giá ứng dụng đặt bàn");
		this.jdbc.sql("UPDATE job.job SET job_types = ARRAY['ASSESSMENT'] WHERE id = :id").param("id", job).update();
		JobContent.selectedBid(this.jdbc, job, product, owner, this.mentor, SIGNED);
		UUID milestone = UUID.randomUUID();
		long price = 600_000;
		JobContent.confirmedAgreement(this.jdbc, job, owner, this.mentor, true, milestone, "Báo cáo đánh giá", price,
				SIGNED);
		JobContent.crewTerms(this.jdbc, job, this.mentor, SIGNED);
		JobContent.share(this.jdbc, job, this.mentor, null, 100);
		JobContent.board(this.jdbc, job, product, "DGA");
		UUID report = JobContent.page(this.jdbc, job, this.mentor, "Báo cáo đánh giá",
				"## Đánh giá\nỨng dụng chạy ổn.\n\n## Đề xuất\n1. Sửa lỗi hai khách đặt trùng bàn.\n",
				ACCEPTED.minus(Duration.ofDays(2)));
		UUID handover = UUID.randomUUID();
		JobContent.milestone(this.jdbc, job, milestone, 0, "Báo cáo đánh giá", price, "ACCEPTED", FUNDED, ACCEPTED, handover,
				SIGNED);
		JobContent.fund(this.jdbc, job, product, milestone, owner, price, FUNDED);
		JobContent.release(this.jdbc, "RELEASE", UUID.randomUUID(), job, product, milestone, this.mentor, price, 100, price,
				ACCEPTED);
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at, decided_at, decided_by,
				    report_page_id, report_version_no)
				VALUES (:id, :job, :product, :milestone, 1, 'Báo cáo đánh giá', 1, :mentor, 'ACCEPTED', 3, :transition, :at, :at,
				    :decided, :owner, :report, 1)
				""")
			.param("id", handover)
			.param("job", job)
			.param("product", product)
			.param("milestone", milestone)
			.param("mentor", this.mentor)
			.param("transition", UUID.randomUUID())
			.param("at", Timestamp.from(ACCEPTED.minus(Duration.ofDays(1))))
			.param("decided", Timestamp.from(ACCEPTED))
			.param("owner", owner)
			.param("report", report)
			.update();
		UUID followUp = UUID.randomUUID();
		Timestamp written = Timestamp.from(WRITTEN);
		this.jdbc.sql("""
				INSERT INTO delivery.follow_up (id, job_id, product_id, handover_id, report_page_id, report_version_no,
				    recommendation_no, title, job_types, skill_ids, scope, criteria, effort_days, status, mentor_account_id,
				    created_at, updated_at)
				VALUES (:id, :job, :product, :handover, :report, 1, 1, 'Sửa lỗi đặt trùng bàn', ARRAY['BUG_FIX'], '{}',
				    'Sửa lỗi hai khách đặt trùng bàn.', ARRAY['Hai khách không đặt trùng bàn'], 5, 'DRAFTING', :mentor, :at, :at)
				""").param("id", followUp).param("job", job).param("product", product).param("handover", handover)
			.param("report", report).param("mentor", this.mentor).param("at", written).update();
		return followUp;
	}

	/**
	 * The second Active Job: the cross-role member's own Product, mentored by the Mentor, whose one Crew member,
	 * Crew Chat message, and Work Item note are the {@code CROSS_*} canaries its Customer never reads. Like every
	 * running Job, it has its selected bid, its confirmed Agreement, whose Milestone waits for funding, and Crew Terms
	 * version 1 (review iteration 4, G9).
	 */
	private void secondJob() {
		UUID product = JobContent.product(this.jdbc, this.crossRole, "Sản phẩm thứ hai");
		UUID job = JobContent.activeJob(this.jdbc, product, this.crossRole, this.mentor, "Việc thứ hai");
		JobContent.selectedBid(this.jdbc, job, product, this.crossRole, this.mentor, SIGNED);
		UUID milestone = UUID.randomUUID();
		JobContent.confirmedAgreement(this.jdbc, job, this.crossRole, this.mentor, false, milestone, "Sửa lỗi thứ hai",
				500_000, SIGNED);
		JobContent.milestone(this.jdbc, job, milestone, 0, "Sửa lỗi thứ hai", 500_000, "PLANNED", null, null, null, SIGNED);
		UUID member = JobContent.canaryMember(this.jdbc, value("CROSS_CREW_NAME"), value("CROSS_CREW_EMAIL"),
				value("CROSS_LINKEDIN"), null);
		UUID memberRow = JobContent.crewRow(this.jdbc, job, member, "ACTIVE", null);
		JobContent.crewTerms(this.jdbc, job, this.mentor, SIGNED);
		JobContent.share(this.jdbc, job, this.mentor, null, 60);
		JobContent.share(this.jdbc, job, member, memberRow, 40);
		UUID chat = JobContent.chat(this.jdbc, job, product, "CREW");
		JobContent.message(this.jdbc, chat, this.mentor, value("CROSS_CREW_CHAT"), WRITTEN);
		JobContent.chat(this.jdbc, job, product, "JOB");
		JobContent.board(this.jdbc, job, product, "VHT");
		UUID item = JobContent.workItem(this.jdbc, job, null, null, MutableClock.DEFAULT_INSTANT);
		JobContent.note(this.jdbc, item, job, this.mentor, value("CROSS_WORK_ITEM_NOTE"), WRITTEN);
		JobContent.page(this.jdbc, job, this.mentor, value("CROSS_PAGE"), WRITTEN);
	}

}
