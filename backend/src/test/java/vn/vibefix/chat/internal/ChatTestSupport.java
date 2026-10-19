package vn.vibefix.chat.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/** Products, Jobs, and Mentors with a free Student, for the chat tests. */
final class ChatTestSupport {

	private ChatTestSupport() {
	}

	static UUID product(JdbcClient jdbc, UUID owner) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
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

	/** A Job in {@code state}; past Open it records {@code mentor} as selected at {@code now}. */
	static UUID job(JdbcClient jdbc, UUID product, UUID owner, String state, UUID mentor, Instant now) {
		UUID id = UUID.randomUUID();
		boolean open = "OPEN".equals(state);
		jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, :state, 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['BUG_FIX'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :selected, 1, :now, :now)
				""")
			.param("id", id)
			.param("product", product)
			.param("owner", owner)
			.param("state", state)
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.param("mentor", open ? null : mentor)
			.param("application", open ? null : UUID.randomUUID())
			.param("selected", open ? null : Timestamp.from(now))
			.update();
		return id;
	}

	static UUID mentorWithFreeStudent(JdbcClient jdbc, String name) {
		UUID mentor = TeamTestSupport.mentor(jdbc, MentorApprovalStatus.APPROVED, name, List.of(TeamTestSupport.REACT),
				"React");
		UUID team = UUID.randomUUID();
		jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', now())")
			.param("id", team)
			.param("mentor", mentor)
			.update();
		UUID student = TeamTestSupport.student(jdbc, "Sinh viên của " + name, true, List.of(TeamTestSupport.REACT),
				"React", LocalDate.parse("2026-09-30"));
		jdbc.sql("INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, now())")
			.param("id", UUID.randomUUID())
			.param("team", team)
			.param("student", student)
			.update();
		return mentor;
	}

}
