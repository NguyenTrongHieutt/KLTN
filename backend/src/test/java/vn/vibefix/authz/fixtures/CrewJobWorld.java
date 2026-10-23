package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * An Active Job of the owner's Product with its Crew (AD-41), built by {@link ProductFixture} so that
 * the matrix's {@link MatrixCaller#OWNER} is this Job's Customer too. It seeds the Crew Chat
 * ({@code {crewChatId}}) and realizes:
 * <ul>
 * <li>{@link MatrixCaller#JOB_MENTOR}, the Job's Mentor;</li>
 * <li>one Student per Crew state: {@code ASSIGNED}, {@code ACTIVE}, {@code INACTIVE} and a former member,
 * the last two with a window that ended before the {@code CREW_CHAT} canary was written;</li>
 * <li>{@link MatrixCaller#ADMIN_TEAM_ISSUE_GRANT}, an Administrator holding a grant on the Crew Chat;</li>
 * <li>{@link MatrixCaller#ADMIN_DISPUTE_GRANT}, the Administrator assigned to the Dispute
 * ({@code {disputeId}}), holding its read grant;</li>
 * <li>{@link MatrixCaller#ADMIN_ABUSE_REPORT_GRANT}, the Administrator assigned to the active member's Abuse
 * Report ({@code {reportId}}) on the {@code CREW_CHAT} canary message ({@code {messageId}}), holding the
 * grant on that one message.</li>
 * </ul>
 * The Mentor's {@code WORK_ITEM_NOTE} note is {@code {workItemNoteId}}.
 * It also seeds the Job's confirmed Agreement with one Milestone ({@code {crewMilestoneId}}) and its
 * Board ({@code {boardId}}) with the criterion's seeded Story ({@code {workItemId}}), which carries the
 * Mentor's {@code WORK_ITEM_NOTE} note and {@code ATTACHMENT} file ({@code {attachmentId}}), and a Page
 * ({@code {pageId}}, version {@code {versionNo}}), all written by the Mentor after the windows ended, and
 * a Milestone Handover ({@code {handoverId}}) whose Work Item note is the {@code WORK_ITEM_NOTE} canary, and
 * the active member's Team Issue ({@code {teamIssueId}}) about a Rating, waiting for the Mentor's answer.
 * The Mentor's earlier, ended Job holds the former member's {@code PRIVATE_FEEDBACK} about the Mentor, and
 * the Job a drafted follow-up ({@code {followUpId}}) and the Customer's open due-date Change Request
 * ({@code {changeRequestId}}) without the Mentor's impact.
 */
final class CrewJobWorld {

	private static final Instant SIGNED = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(10));

	private static final Instant WINDOW_END = MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(5));

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private UUID mentor;

	private UUID assigned;

	private UUID active;

	private UUID inactive;

	private UUID former;

	private UUID admin;

	private UUID disputeAdmin;

	private UUID abuseAdmin;

	CrewJobWorld(JdbcClient jdbc, SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.sessions = sessions;
	}

	void seed(MatrixWorld world, UUID owner, UUID product) {
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.mentor = TeamTestSupport.mentor(this.jdbc, MentorApprovalStatus.APPROVED, "Người hướng dẫn nhóm ma trận",
				List.of(TeamTestSupport.REACT), "React");
		UUID job = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Việc nhóm ma trận', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :signed, :lapses, :mentor, :application, :signed, 5,
				    :signed, :now)
				""")
			.param("id", job)
			.param("product", product)
			.param("owner", owner)
			.param("signed", Timestamp.from(SIGNED))
			.param("lapses", Timestamp.from(SIGNED.plus(Duration.ofDays(14))))
			.param("mentor", this.mentor)
			.param("application", UUID.randomUUID())
			.param("now", now)
			.update();
		this.assigned = crewRow(job, "Sinh viên được giao", "ASSIGNED");
		this.active = crewRow(job, "Sinh viên đang làm", "ACTIVE");
		this.inactive = crewRow(job, "Sinh viên tạm dừng", "INACTIVE");
		this.former = crewRow(job, "Sinh viên đã rời", "RELEASED");
		UUID chat = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO chat.chat (id, job_id, product_id, kind, opened_at) VALUES (:id, :job, :product, 'CREW', :signed)
				""").param("id", chat).param("job", job).param("product", product).param("signed", Timestamp.from(SIGNED)).update();
		message(chat, "Chào cả nhóm, mình bắt đầu nhé", SIGNED.plus(Duration.ofHours(1)));
		UUID canaryMessage = message(chat, Canaries.load().get("CREW_CHAT").value(),
				MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1)));
		world.put("messageId", canaryMessage);
		this.admin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.jdbc.sql("""
				INSERT INTO chat.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id)
				VALUES (:grant, :admin, :job, CAST(:items AS JSONB), :reason)
				""")
			.param("grant", UUID.randomUUID())
			.param("admin", this.admin)
			.param("job", job)
			.param("items", "[{\"scope\":\"CREW_CHAT\",\"objectId\":\"\",\"accountId\":\"\"}]")
			.param("reason", UUID.randomUUID())
			.update();
		world.put("crewChatId", chat);
		UUID milestone = UUID.randomUUID();
		UUID criterion = UUID.randomUUID();
		String content = """
				{"scope":"Sửa lỗi","priceVnd":800000,"codeJob":true,"assessment":false,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Sửa lỗi trùng giờ","priceVnd":800000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Hai khách không đặt trùng bàn","checkSteps":"1. Mở. 2. Đặt."}]}]}
				""".formatted(milestone, criterion);
		this.jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :signed)
				""").param("job", job).param("content", content).param("mentor", this.mentor)
			.param("signed", Timestamp.from(SIGNED)).update();
		this.jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', true, :owner, :signed, :mentor, :signed)
				""").param("job", job).param("content", content).param("owner", owner).param("mentor", this.mentor)
			.param("signed", Timestamp.from(SIGNED)).update();
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'VNM', 2, :now)
				""").param("job", job).param("product", product).param("now", now).update();
		UUID item = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.work_item (id, job_id, number, type, title, description, criterion_id, milestone_id, created_at,
				    updated_at)
				VALUES (:id, :job, 1, 'STORY', 'Hai khách không đặt trùng bàn', '1. Mở. 2. Đặt.', :criterion, :milestone, :now, :now)
				""")
			.param("id", item)
			.param("job", job)
			.param("criterion", criterion)
			.param("milestone", milestone)
			.param("now", now)
			.update();
		world.put("boardId", job);
		world.put("crewMilestoneId", milestone);
		UUID teamIssue = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, rating_id, raiser_account_id, mentor_account_id,
				    reason, phase, answer_days, escalation_days, raised_at, answer_due_at)
				VALUES (:id, :job, :product, 'RATING', :rating, :raiser, :mentor, 'Điểm chưa đúng', 'ANSWER', 5, 5, :raised, :due)
				""")
			.param("id", teamIssue)
			.param("job", job)
			.param("product", product)
			.param("rating", UUID.randomUUID())
			.param("raiser", this.active)
			.param("mentor", this.mentor)
			.param("raised", now)
			.param("due", Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(5))))
			.update();
		world.put("teamIssueId", teamIssue);
		world.put("workItemId", item);
		Timestamp written = Timestamp.from(MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1)));
		UUID attachment = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.attachment (id, work_item_id, job_id, kind, file_id, file_name, media_type, size_bytes,
				    author_account_id, added_at)
				VALUES (:id, :item, :job, 'FILE', :file, :name, 'application/pdf', 2048, :mentor, :at)
				""")
			.param("id", attachment)
			.param("item", item)
			.param("job", job)
			.param("file", UUID.randomUUID())
			.param("name", Canaries.load().get("ATTACHMENT").value())
			.param("mentor", this.mentor)
			.param("at", written)
			.update();
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, attachment_id, at)
				VALUES (:id, :item, :job, :mentor, 'ATTACHED', '{}'::jsonb, :attachment, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("item", item)
			.param("job", job)
			.param("mentor", this.mentor)
			.param("attachment", attachment)
			.param("at", written)
			.update();
		UUID note = UUID.randomUUID();
		world.put("workItemNoteId", note);
		this.jdbc.sql("""
				INSERT INTO work.work_item_history (id, work_item_id, job_id, actor_account_id, action, changes, comment, at)
				VALUES (:id, :item, :job, :mentor, 'NOTE', '{}'::jsonb, :note, :at)
				""")
			.param("id", note)
			.param("item", item)
			.param("job", job)
			.param("mentor", this.mentor)
			.param("note", Canaries.load().get("WORK_ITEM_NOTE").value())
			.param("at", written)
			.update();
		world.put("attachmentId", attachment);
		UUID handover = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.handover (id, job_id, product_id, milestone_id, milestone_number, milestone_title, round,
				    mentor_account_id, status, job_version, transition_id, check_by, created_at)
				VALUES (:id, :job, :product, :milestone, 1, 'Sửa lỗi trùng giờ', 1, :mentor, 'SUBMITTED', 5, :transition, :at, :at)
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
				VALUES (:id, :item, 'VNM-1', 'Hai khách không đặt trùng bàn', 'TODO', :note)
				""").param("id", handover).param("item", item).param("note", Canaries.load().get("WORK_ITEM_NOTE").value())
			.update();
		world.put("handoverId", handover);
		UUID followUp = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO delivery.follow_up (id, job_id, product_id, handover_id, report_page_id, report_version_no,
				    recommendation_no, title, job_types, skill_ids, scope, criteria, effort_days, status, mentor_account_id,
				    created_at, updated_at)
				VALUES (:id, :job, :product, :handover, :page, 1, 1, 'Sửa lỗi đặt trùng bàn', ARRAY['BUG_FIX'], '{}',
				    'Sửa lỗi hai khách đặt trùng bàn.', ARRAY['Hai khách không đặt trùng bàn'], 5, 'DRAFTING', :mentor, :at, :at)
				""").param("id", followUp).param("job", job).param("product", product).param("handover", handover)
			.param("page", UUID.randomUUID()).param("mentor", this.mentor).param("at", written).update();
		world.put("followUpId", followUp);
		UUID changeRequest = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.change_request (id, job_id, proposer, proposer_account_id, description, items, status, created_at)
				VALUES (:id, :job, 'CUSTOMER', :owner, 'Cần thêm thời gian', CAST(:items AS JSONB), 'OPEN', :at)
				""")
			.param("id", changeRequest)
			.param("job", job)
			.param("owner", owner)
			.param("items", "[{\"milestoneId\":\"" + milestone + "\",\"number\":1,\"title\":\"Sửa lỗi trùng giờ\","
					+ "\"oldDueOn\":\"2026-11-20\",\"newDueOn\":\"2027-01-20\"}]")
			.param("at", written)
			.update();
		world.put("changeRequestId", changeRequest);
		UUID dispute = UUID.randomUUID();
		this.disputeAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.jdbc.sql("""
				INSERT INTO dispute.dispute (id, job_id, product_id, opened_by, opener_account_id, reason, concern, job_state, status,
				    opened_at, assigned_admin_id, assigned_at, grant_id)
				VALUES (:id, :job, :product, 'CUSTOMER', :owner, 'Giai đoạn chưa đạt', 'NEXT_STATE', 'ACTIVE', 'OPEN', :at,
				    :admin, :at, :grant)
				""").param("id", dispute).param("job", UUID.randomUUID()).param("product", product).param("owner", owner)
			.param("at", written).param("admin", this.disputeAdmin).param("grant", UUID.randomUUID()).update();
		world.put("disputeId", dispute);
		UUID report = UUID.randomUUID();
		UUID reportGrant = UUID.randomUUID();
		this.abuseAdmin = IdentityTestSupport.insertAccount(this.jdbc, Role.ADMINISTRATOR, null, null);
		this.jdbc.sql("""
				INSERT INTO admin.abuse_report (id, reporter_account_id, kind, target_id, job_id, product_id, author_account_id,
				    label, reason, status, created_at, assigned_admin_id, assigned_at, grant_id)
				VALUES (:id, :reporter, 'CREW_CHAT_MESSAGE', :message, :job, :product, :mentor, 'Việc nhóm ma trận',
				    'Lời lẽ xúc phạm', 'OPEN', :at, :admin, :at, :grant)
				""").param("id", report).param("reporter", this.active).param("message", canaryMessage).param("job", job)
			.param("product", product).param("mentor", this.mentor).param("at", written).param("admin", this.abuseAdmin)
			.param("grant", reportGrant).update();
		this.jdbc.sql("""
				INSERT INTO chat.admin_read_grant (grant_id, admin_account_id, job_id, items, reason_id)
				VALUES (:grant, :admin, :job, CAST(:items AS JSONB), :reason)
				""")
			.param("grant", reportGrant)
			.param("admin", this.abuseAdmin)
			.param("job", job)
			.param("items", "[{\"scope\":\"CREW_CHAT\",\"objectId\":\"" + canaryMessage + "\",\"accountId\":\"\"}]")
			.param("reason", report)
			.update();
		world.put("reportId", report);
		world.put("crewCriterionId", criterion);
		world.put("item", "NON_CODE_DATA");
		UUID page = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO work.page (id, job_id, title, version_no, created_by_account_id, created_at, updated_at)
				VALUES (:id, :job, 'Ghi chú kỹ thuật', 1, :mentor, :at, :at)
				""").param("id", page).param("job", job).param("mentor", this.mentor).param("at", written).update();
		this.jdbc.sql("""
				INSERT INTO work.page_version (page_id, version_no, title, body, author_account_id, created_at)
				VALUES (:id, 1, 'Ghi chú kỹ thuật', '# Cách chạy', :mentor, :at)
				""").param("id", page).param("mentor", this.mentor).param("at", written).update();
		world.put("pageId", page);
		world.put("versionNo", 1);
		UUID earlier = UUID.randomUUID();
		Instant ended = SIGNED.minus(Duration.ofDays(30));
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
			.param("comment", Canaries.load().get("PRIVATE_FEEDBACK").value())
			.param("at", Timestamp.from(ended.minus(Duration.ofDays(1))))
			.param("deleteAfter", Timestamp.from(ended.plus(Duration.ofDays(730))))
			.update();
	}

	Optional<RequestPostProcessor> realize(MatrixCaller caller) {
		return switch (caller) {
			case JOB_MENTOR -> signedIn(this.mentor, Role.MENTOR);
			case CREW_ASSIGNED -> signedIn(this.assigned, Role.STUDENT);
			case CREW_ACTIVE -> signedIn(this.active, Role.STUDENT);
			case CREW_INACTIVE -> signedIn(this.inactive, Role.STUDENT);
			case CREW_FORMER -> signedIn(this.former, Role.STUDENT);
			case ADMIN_TEAM_ISSUE_GRANT -> signedIn(this.admin, Role.ADMINISTRATOR);
			case ADMIN_DISPUTE_GRANT -> signedIn(this.disputeAdmin, Role.ADMINISTRATOR);
			case ADMIN_ABUSE_REPORT_GRANT -> signedIn(this.abuseAdmin, Role.ADMINISTRATOR);
			default -> Optional.empty();
		};
	}

	private Optional<RequestPostProcessor> signedIn(UUID account, Role role) {
		return Optional.of(IdentityTestSupport.signedIn(this.sessions, account, role, MutableClock.DEFAULT_INSTANT));
	}

	/** A Student with a row in {@code status}; every row but ASSIGNED signed and became ACTIVE. */
	private UUID crewRow(UUID job, String name, String status) {
		UUID student = TeamTestSupport.student(this.jdbc, name, true, List.of(TeamTestSupport.REACT), "React",
				VERIFIED_UNTIL);
		boolean signed = !"ASSIGNED".equals(status);
		boolean ended = "INACTIVE".equals(status) || "RELEASED".equals(status);
		boolean released = "RELEASED".equals(status);
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, window_end,
				    released_at, released_reason, created_at)
				VALUES (:id, :job, :student, :status, :signed, :signed, :end, :released, :reason, :created)
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("student", student)
			.param("status", status)
			.param("signed", signed ? Timestamp.from(SIGNED) : null)
			.param("end", ended ? Timestamp.from(WINDOW_END) : null)
			.param("released", released ? Timestamp.from(WINDOW_END) : null)
			.param("reason", released ? "REMOVED" : null)
			.param("created", Timestamp.from(SIGNED.minus(Duration.ofHours(1))))
			.update();
		return student;
	}

	private UUID message(UUID chat, String body, Instant at) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :author, :body, :at)
				""")
			.param("id", id)
			.param("chat", chat)
			.param("author", this.mentor)
			.param("body", body)
			.param("at", Timestamp.from(at))
			.update();
		return id;
	}

}
