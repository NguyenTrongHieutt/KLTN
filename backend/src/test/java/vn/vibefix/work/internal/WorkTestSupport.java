package vn.vibefix.work.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.team.internal.TeamTestSupport;

/** Products, Active Jobs with a confirmed Agreement, and Crew rows, for the work tests. */
final class WorkTestSupport {

	private WorkTestSupport() {
	}

	static UUID product(JdbcClient jdbc, UUID owner, String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, :name, 'Đặt bàn cho quán.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""")
			.param("id", id)
			.param("owner", owner)
			.param("name", name)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
			.update();
		return id;
	}

	/** An Active Assessment Job whose Mentor is {@code mentor}, with Agreement version 1 confirmed. */
	static UUID activeJob(JdbcClient jdbc, UUID product, UUID owner, UUID mentor, List<UUID> milestones,
			List<List<UUID>> criteria, Instant now) {
		return activeJob(jdbc, product, owner, mentor, milestones, criteria, now, "ASSESSMENT");
	}

	/** An Active Job of {@code jobType} whose Mentor is {@code mentor}, with Agreement version 1 confirmed. */
	static UUID activeJob(JdbcClient jdbc, UUID product, UUID owner, UUID mentor, List<UUID> milestones,
			List<List<UUID>> criteria, Instant now, String jobType) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY[:type],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :lapses, :mentor, :application, :now, 5, :now, :now)
				""")
			.param("id", id)
			.param("product", product)
			.param("owner", owner)
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(now.plus(Duration.ofDays(14))))
			.param("mentor", mentor)
			.param("application", UUID.randomUUID())
			.param("type", jobType)
			.update();
		StringBuilder content = new StringBuilder("{\"scope\":\"Đánh giá\",\"priceVnd\":3000000,\"codeJob\":false,")
			.append("\"assessment\":true,\"skills\":[],\"externalAccess\":[],\"milestones\":[");
		for (int m = 0; m < milestones.size(); m++) {
			content.append(m == 0 ? "" : ",")
				.append("{\"id\":\"%s\",\"title\":\"Giai đoạn %d\",\"priceVnd\":1500000,\"dueOn\":\"2026-11-20\",".formatted(milestones.get(m), m + 1))
				.append("\"revisionRounds\":2,\"criteria\":[");
			for (int c = 0; c < criteria.get(m).size(); c++) {
				content.append(c == 0 ? "" : ",")
					.append("{\"id\":\"%s\",\"statement\":\"Tiêu chí %d.%d\",\"checkSteps\":\"Cách kiểm tra %d.%d\"}"
						.formatted(criteria.get(m).get(c), m + 1, c + 1, m + 1, c + 1));
			}
			content.append("]}");
		}
		content.append("]}");
		jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", id).param("content", content.toString()).param("mentor", mentor)
			.param("now", Timestamp.from(now)).update();
		jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", id).param("content", content.toString()).param("owner", owner).param("mentor", mentor)
			.param("now", Timestamp.from(now)).update();
		return id;
	}

	static UUID student(JdbcClient jdbc, String name) {
		return TeamTestSupport.student(jdbc, name, true, List.of(TeamTestSupport.REACT), "React", LocalDate.parse("2026-09-30"));
	}

	/** A Crew row in {@code status}; every row but ASSIGNED signed and became ACTIVE at {@code signed}. */
	static void crewRow(JdbcClient jdbc, UUID job, UUID student, String status, @Nullable Instant signed) {
		jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, student_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :student, :status, :signed, :signed, now())
				""")
			.param("id", UUID.randomUUID())
			.param("job", job)
			.param("student", student)
			.param("status", status)
			.param("signed", signed == null ? null : Timestamp.from(signed))
			.update();
	}

}
