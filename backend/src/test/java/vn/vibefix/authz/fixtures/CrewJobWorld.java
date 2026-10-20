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
 * <li>{@link MatrixCaller#ADMIN_TEAM_ISSUE_GRANT}, an Administrator holding a grant on the Crew Chat.</li>
 * </ul>
 * It also seeds the Job's confirmed Agreement with one Milestone ({@code {crewMilestoneId}}) and its
 * Board ({@code {boardId}}) with the criterion's seeded Story ({@code {workItemId}}).
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
		message(chat, Canaries.load().get("CREW_CHAT").value(), MutableClock.DEFAULT_INSTANT.minus(Duration.ofDays(1)));
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
		world.put("workItemId", item);
	}

	Optional<RequestPostProcessor> realize(MatrixCaller caller) {
		return switch (caller) {
			case JOB_MENTOR -> signedIn(this.mentor, Role.MENTOR);
			case CREW_ASSIGNED -> signedIn(this.assigned, Role.STUDENT);
			case CREW_ACTIVE -> signedIn(this.active, Role.STUDENT);
			case CREW_INACTIVE -> signedIn(this.inactive, Role.STUDENT);
			case CREW_FORMER -> signedIn(this.former, Role.STUDENT);
			case ADMIN_TEAM_ISSUE_GRANT -> signedIn(this.admin, Role.ADMINISTRATOR);
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

	private void message(UUID chat, String body, Instant at) {
		this.jdbc.sql("""
				INSERT INTO chat.message (id, chat_id, author_account_id, body, sent_at) VALUES (:id, :chat, :author, :body, :at)
				""")
			.param("id", UUID.randomUUID())
			.param("chat", chat)
			.param("author", this.mentor)
			.param("body", body)
			.param("at", Timestamp.from(at))
			.update();
	}

}
