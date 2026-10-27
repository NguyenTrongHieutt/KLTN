package vn.vibefix.support;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * An Active Job of Lan's Product whose Mentor Hùng runs it with Vy and Minh, both active, and whose one
 * Milestone ({@link #milestone}) is Funded with {@link #PRICE} paid in and pinned to Crew Terms version 1:
 * Hùng 20%, Vy 40%, Minh 40% (AD-41). Team Issue tests (AD-40) start here.
 */
public record HeldCrewJob(UUID lan, UUID hung, UUID vy, UUID minh, UUID product, UUID job, UUID milestone) {

	public static final long PRICE = 1_000_000;

	public static HeldCrewJob create(JdbcClient jdbc, Instant now) {
		UUID lan = IdentityTestSupport.insertAccount(jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		UUID vy = student(jdbc, "Trần Thảo Vy");
		UUID minh = student(jdbc, "Nguyễn Văn Minh");
		UUID product = UUID.randomUUID();
		Timestamp at = Timestamp.from(now);
		jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn Quán Mây', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", product).param("owner", lan)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		UUID job = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at, agreement_version_no)
				VALUES (:id, :product, :owner, 'ACTIVE', 'Sửa lỗi đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{"TEAM_ISSUE_ANSWER_DAYS": 5, "TEAM_ISSUE_ESCALATION_DAYS": 5}'::jsonb, 14,
				    :now, :now, :mentor, :application, :now, 5, :now, :now, 1)
				""").param("id", job).param("product", product).param("owner", lan).param("now", at).param("mentor", hung)
			.param("application", UUID.randomUUID()).update();
		UUID milestone = UUID.randomUUID();
		String content = """
				{"scope":"Đánh giá","priceVnd":1000000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
				 "milestones":[{"id":"%s","title":"Giai đoạn 1","priceVnd":1000000,"dueOn":"2026-11-20","revisionRounds":2,
				   "criteria":[{"id":"%s","statement":"Tiêu chí 1","checkSteps":"Cách kiểm tra 1"}]}]}
				""".formatted(milestone, UUID.randomUUID());
		jdbc.sql("""
				INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
				VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
				""").param("job", job).param("content", content).param("mentor", hung).param("now", at).update();
		jdbc.sql("""
				INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
				    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
				VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
				""").param("job", job).param("content", content).param("owner", lan).param("mentor", hung).param("now", at)
			.update();
		jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state, funded_at,
				    crew_terms_version_no)
				VALUES (:id, :job, 0, 'Giai đoạn 1', :price, 2, now(), 'FUNDED', now(), 1)
				""").param("id", milestone).param("job", job).param("price", PRICE).update();
		jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 1, 'IN_FORCE', 40, :mentor, now())
				""").param("job", job).param("mentor", hung).update();
		share(jdbc, job, hung, null, 20);
		Instant signed = now.minus(Duration.ofDays(3));
		for (UUID student : List.of(vy, minh)) {
			UUID row = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
					VALUES (:id, :job, :student, 'ACTIVE', :signed, :signed, now())
					""").param("id", row).param("job", job).param("student", student).param("signed", Timestamp.from(signed))
				.update();
			share(jdbc, job, student, row, 40);
		}
		jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at) VALUES (:id, :job, :product, now())
				""").param("id", milestone).param("job", job).param("product", product).update();
		UUID order = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO payment.payment_order (id, job_id, product_id, milestone_id, customer_account_id, purpose,
				    amount_vnd, agreement_version_no, txn_ref, status, created_at, expires_at, finished_at)
				VALUES (:id, :job, :product, :milestone, :customer, 'FUNDING', :amount, 1, :ref, 'PAID', :now, :now, :now)
				""").param("id", order).param("job", job).param("product", product).param("milestone", milestone)
			.param("customer", lan).param("amount", PRICE)
			.param("ref", "L" + Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), 36)).param("now", at)
			.update();
		jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				VALUES (:id, :key, 'FUND', 'MILESTONE', :job, :product, :milestone, :order, :amount, :no, now())
				""").param("id", UUID.randomUUID()).param("key", "FUND:" + UUID.randomUUID()).param("job", job)
			.param("product", product).param("milestone", milestone).param("order", order).param("amount", PRICE)
			.param("no", String.valueOf(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))).update();
		return new HeldCrewJob(lan, hung, vy, minh, product, job, milestone);
	}

	/** The Job's Rating Window, its Crew's Ratings published at {@code published}; Hùng rated {@code student}. */
	public UUID rating(JdbcClient jdbc, UUID student, Instant published) {
		jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at, crew_published_at)
				VALUES (:job, :product, :owner, :mentor, 'Sửa lỗi đặt bàn', :opened, :published, :published, :published)
				ON CONFLICT (job_id) DO NOTHING
				""").param("job", this.job).param("product", this.product).param("owner", this.lan).param("mentor", this.hung)
			.param("opened", Timestamp.from(published.minus(Duration.ofDays(14))))
			.param("published", Timestamp.from(published))
			.update();
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at)
				VALUES (:id, :job, 'MENTOR_TO_STUDENT', :mentor, :student, 2, 'Còn chậm', :at)
				""").param("id", id).param("job", this.job).param("mentor", this.hung).param("student", student)
			.param("at", Timestamp.from(published.minus(Duration.ofDays(1))))
			.update();
		return id;
	}

	private static UUID student(JdbcClient jdbc, String name) {
		return TeamTestSupport.student(jdbc, name, true, List.of(TeamTestSupport.REACT), "React", LocalDate.parse("2026-09-30"));
	}

	private static void share(JdbcClient jdbc, UUID job, UUID party, UUID row, int percent) {
		jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 1, :party, :row, :percent)
				""").param("job", job).param("party", party).param("row", row).param("percent", percent).update();
	}

}
