package vn.vibefix.admin.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.AdministratorTestSupport;
import vn.vibefix.identity.internal.AdministratorTestSupport.Administrator;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * ADM-9 (FR-62, UX-DR110): every module's figures in their groups, in the fixed order; the private-feedback figure
 * stays out below 5 feedbacks across 3 Mentors. Story 17.2 adds the rates (completion, on time, first-pass
 * approvals, acceptance shares), the Rating averages without hidden Ratings, and Team Issues by outcome; a
 * rate without a denominator is left out. Story 20.3 counts users by Verification and by the roles they hold per
 * Job, with no account-role row.
 */
class StatisticsIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Autowired
	private ApplicationContext context;

	@Test
	void theFiguresComeGroupedAndCountsOnly() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, "Vi phạm");
		TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT), "React");
		TeamTestSupport.student(this.jdbc, "Trần Thảo Vy", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);
		IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-thong-ke", "3.1");

		this.mvc.perform(get("/api/v1/admin/statistics").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[0].group").value("usersByVerification"))
			.andExpect(jsonPath("$.groups[1].group").value("usersByJobRole"))
			// Story 20.3: no account-role row; the five shim accounts have GitHub linked, the Google one has not.
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByRole')]").isEmpty())
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByVerification')].counts[?(@.key == 'GITHUB')].value").value(5))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByVerification')].counts[?(@.key == 'LINKEDIN')].value").value(0))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByVerification')].counts[?(@.key == 'STUDENT')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByJobRole')].counts[?(@.key == 'CUSTOMER')].value").value(0))
			// Story 20.3: usersByVerification's STUDENT replaces the students-by-verification group.
			.andExpect(jsonPath("$.groups[?(@.group == 'studentsByVerification')]").isEmpty())
			// Story 19.7: nobody is approved, so no Mentor or application count by decision exists.
			.andExpect(jsonPath("$.groups[?(@.group == 'mentorsByApproval')]").isEmpty())
			.andExpect(jsonPath("$.groups[?(@.group == 'mentorApplicationsByDecision')]").isEmpty())
			.andExpect(jsonPath("$.groups[?(@.group == 'lockedAccounts')].counts[?(@.key == 'LOCKED')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'privateFeedback')].counts.length()").value(0))
			.andExpect(jsonPath("$.groups.length()").value(AdminStatisticsController.ORDER.size()));
	}

	@Test
	void usersCountOnceInEachRoleTheyHoldInAnyJobAndByEachVerificationTheyHold() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, lan);
		UUID hung = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-hung", "3.1");
		IdentityTestSupport.linkGitHub(this.jdbc, hung);
		UUID vy = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-vy", "3.1");
		IdentityTestSupport.verifyStudent(this.jdbc, vy, LocalDate.parse("2026-09-30"));
		UUID minh = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-minh", "3.1");
		// Lan is the Customer of Jobs A and C and the Mentor of Job B; Hung the Mentor of Job A and the Customer of
		// Job B; Vy a Crew member of Job A; Minh's Crew row on Job B was never activated, so it counts for nothing.
		UUID productA = product(lan);
		UUID jobA = job(productA, lan, hung, "ACTIVE", true);
		UUID jobB = job(product(hung), hung, lan, "AGREEMENT", true);
		job(productA, lan, hung, "CLOSED", true);
		crewRow(jobA, vy, true);
		crewRow(jobB, minh, false);

		this.mvc.perform(get("/api/v1/admin/statistics").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByJobRole')].counts[?(@.key == 'CUSTOMER')].value").value(2))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByJobRole')].counts[?(@.key == 'MENTOR')].value").value(2))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByJobRole')].counts[?(@.key == 'CREW')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByVerification')].counts[?(@.key == 'GITHUB')].value").value(2))
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByVerification')].counts[?(@.key == 'STUDENT')].value").value(1));
	}

	@Test
	void aRenewedVerificationCountsOnceAndAnUnlinkedOrRevokedOneNotAtAll() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		UUID renewed = TeamTestSupport.student(this.jdbc, "Gia hạn", true, List.of(), "", LocalDate.parse("2026-09-30"));
		this.jdbc.sql("""
				INSERT INTO identity.verification (id, account_id, kind, provider, subject_hmac, key_version, consent_version,
				    verified_at, valid_until, ended_at, ended_reason)
				VALUES (:id, :account, 'STUDENT', 'EMAIL_CODE', :hmac, 1, '1.0', now() - interval '1 year', DATE '2025-09-30',
				        now(), 'REPLACED')
				""").param("id", UUID.randomUUID()).param("account", renewed)
			.param("hmac", IdentityTestSupport.fakeHmac("renewed-old")).update();
		for (String reason : List.of("UNLINKED", "REVOKED")) {
			UUID ended = TeamTestSupport.student(this.jdbc, reason, true, List.of(), "", LocalDate.parse("2026-09-30"));
			this.jdbc.sql("""
					UPDATE identity.verification SET ended_at = now(), ended_reason = :reason, facts = NULL WHERE account_id = :id
					""").param("id", ended).param("reason", reason).update();
		}

		this.mvc.perform(get("/api/v1/admin/statistics").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[?(@.group == 'usersByVerification')].counts[?(@.key == 'STUDENT')].value").value(1));
	}

	@Test
	void ratesAndAveragesFollowTheFr62Formulas() throws Exception {
		Administrator ha = AdministratorTestSupport.create(this.context, "ha.phuong@vibefix.test", "Hà Phương");
		this.mvc.perform(get("/api/v1/admin/statistics").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[?(@.group == 'rates')].counts.length()").value(0))
			.andExpect(jsonPath("$.groups[?(@.group == 'ratings')].counts.length()").value(0));

		UUID lan = IdentityTestSupport.insertAccount(this.jdbc, Role.CUSTOMER, null);
		UUID hung = TeamTestSupport.mentor(this.jdbc, "Đỗ Mạnh Hùng", List.of(TeamTestSupport.REACT),
				"React");
		UUID product = product(lan);
		List<UUID> closed = List.of(job(product, lan, hung, "CLOSED", true), job(product, lan, hung, "CLOSED", true),
				job(product, lan, hung, "CLOSED", true));
		job(product, lan, hung, "CANCELLED", true);
		job(product, lan, hung, "CANCELLED", false);
		// due 2026-11-20 ends at 17:00 UTC: one handover in time, one an hour late
		milestone(closed.get(0), Instant.parse("2026-11-20T16:00:00Z"), false);
		milestone(closed.get(1), Instant.parse("2026-11-20T18:00:00Z"), true);
		rating(closed.get(0), product, lan, hung, 5, false);
		rating(closed.get(1), product, lan, hung, 4, false);
		rating(closed.get(2), product, lan, hung, 1, true);
		reviews(closed.get(0), product, List.of(List.of("APPROVED"), List.of("RETURNED", "APPROVED"), List.of("APPROVED")));
		teamIssue(closed.get(0), product, null, null);
		teamIssue(closed.get(1), product, Instant.parse("2026-11-22T03:00:00Z"), null);
		teamIssue(closed.get(2), product, null, "ACCEPTED");

		this.mvc.perform(get("/api/v1/admin/statistics").with(admin(ha))).andExpect(status().isOk())
			.andExpect(jsonPath("$.groups[?(@.group == 'rates')].counts[?(@.key == 'COMPLETION')].value").value(750))
			.andExpect(jsonPath("$.groups[?(@.group == 'rates')].counts[?(@.key == 'COMPLETION')].unit").value("PERCENT_TENTHS"))
			.andExpect(jsonPath("$.groups[?(@.group == 'rates')].counts[?(@.key == 'ON_TIME')].value").value(500))
			.andExpect(jsonPath("$.groups[?(@.group == 'acceptances')].counts[?(@.key == 'MANUAL_SHARE')].value").value(500))
			.andExpect(jsonPath("$.groups[?(@.group == 'acceptances')].counts[?(@.key == 'AUTOMATIC_SHARE')].value").value(500))
			.andExpect(jsonPath("$.groups[?(@.group == 'acceptances')].counts[?(@.key == 'MANUAL')].unit").value("COUNT"))
			.andExpect(jsonPath("$.groups[?(@.group == 'workItemReviews')].counts[?(@.key == 'FIRST_PASS_SHARE')].value")
				.value(667))
			.andExpect(jsonPath("$.groups[?(@.group == 'ratings')].counts[?(@.key == 'CUSTOMER_TO_MENTOR_AVERAGE')].value")
				.value(45))
			.andExpect(jsonPath("$.groups[?(@.group == 'ratings')].counts[?(@.key == 'CUSTOMER_TO_MENTOR_AVERAGE')].unit")
				.value("AVERAGE_TENTHS"))
			.andExpect(jsonPath("$.groups[?(@.group == 'ratings')].counts[?(@.key == 'CUSTOMER_TO_MENTOR_COUNT')].value")
				.value(2))
			.andExpect(jsonPath("$.groups[?(@.group == 'teamIssuesByOutcome')].counts[?(@.key == 'OPEN')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'teamIssuesByOutcome')].counts[?(@.key == 'ESCALATED')].value").value(1))
			.andExpect(jsonPath("$.groups[?(@.group == 'teamIssuesByOutcome')].counts[?(@.key == 'ACCEPTED')].value").value(1));
	}

	private UUID product(UUID owner) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO product.product (id, customer_account_id, name, description, ai_tools, repository_github_id,
				    repository_full_name, repository_owner_type, repository_default_branch, public_notice_version,
				    copy_permission_version, detection_notice_version, acknowledged_at, created_at)
				VALUES (:id, :owner, 'Sổ đặt bàn', 'Đặt bàn.', ARRAY['LOVABLE'], :github, 'lan/so-dat-ban', 'User', 'main',
				    '1.0', '1.0', '1.0', now(), now())
				""").param("id", id).param("owner", owner)
			.param("github", ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
		return id;
	}

	/** An Assessment Job in {@code state}, with Agreement version 1 confirmed when {@code confirmed}. */
	private UUID job(UUID product, UUID owner, UUID mentor, String state, boolean confirmed) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, problem, expected, app_users,
				    job_types, budget, deadline, rules, selection_days, opened_at, lapses_at, mentor_account_id,
				    selected_application_id, selected_at, version, created_at, updated_at)
				VALUES (:id, :product, :owner, :state, 'Đánh giá sổ đặt bàn', 'Lỗi', 'Đúng', 'Khách', ARRAY['ASSESSMENT'],
				    'UNDER_2M', DATE '2026-12-01', '{}'::jsonb, 14, :now, :now, :mentor, :application, :now, 5, :now, :now)
				""").param("id", id).param("product", product).param("owner", owner).param("state", state).param("now", now)
			.param("mentor", mentor).param("application", UUID.randomUUID()).update();
		if (confirmed) {
			String content = """
					{"scope":"Đánh giá","priceVnd":800000,"codeJob":false,"assessment":true,"skills":[],"externalAccess":[],
					 "milestones":[]}""";
			this.jdbc.sql("""
					INSERT INTO job.agreement_version (job_id, version_no, status, content, author_account_id, created_at)
					VALUES (:job, 1, 'SHARED', CAST(:content AS JSONB), :mentor, :now)
					""").param("job", id).param("content", content).param("mentor", mentor).param("now", now).update();
			this.jdbc.sql("""
					INSERT INTO job.confirmed_agreement (job_id, version_no, draft_version_no, content, crew_usernames,
					    freeze_committed, customer_account_id, approved_at, mentor_account_id, confirmed_at)
					VALUES (:job, 1, 1, CAST(:content AS JSONB), '{}', false, :owner, :now, :mentor, :now)
					""").param("job", id).param("content", content).param("owner", owner).param("mentor", mentor)
				.param("now", now).update();
		}
		return id;
	}

	/** An accepted Milestone due 2026-11-20 whose accepted handover was submitted at {@code submitted}. */
	private void milestone(UUID job, Instant submitted, boolean automatic) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, due_on, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no, submitted_at, handover_id, accepted_at, auto_accepted)
				VALUES (:id, :job, 0, 'Đánh giá', 800000, DATE '2026-11-20', 2, :now, 'ACCEPTED', :now, 1, :submitted,
				    :handover, :accepted, :automatic)
				""").param("id", UUID.randomUUID()).param("job", job).param("now", now)
			.param("submitted", Timestamp.from(submitted)).param("handover", UUID.randomUUID())
			.param("accepted", Timestamp.from(submitted.plusSeconds(3600))).param("automatic", automatic).update();
	}

	private void rating(UUID job, UUID product, UUID customer, UUID mentor, int stars, boolean hidden) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO reputation.rating_window (job_id, product_id, customer_account_id, mentor_account_id, job_title,
				    opened_at, ends_at, pair_published_at)
				VALUES (:job, :product, :customer, :mentor, 'Đánh giá sổ đặt bàn', :now, :ends, :now)
				""").param("job", job).param("product", product).param("customer", customer).param("mentor", mentor)
			.param("now", now).param("ends", Timestamp.from(this.clock.instant().plusSeconds(14 * 86_400))).update();
		this.jdbc.sql("""
				INSERT INTO reputation.rating (id, job_id, kind, rater_account_id, ratee_account_id, stars, comment, created_at,
				    hidden_at, hidden_reason)
				VALUES (:id, :job, 'CUSTOMER_TO_MENTOR', :customer, :mentor, :stars, 'Nhận xét', :now, :hidden, :reason)
				""").param("id", UUID.randomUUID()).param("job", job).param("customer", customer).param("mentor", mentor)
			.param("stars", stars).param("now", now).param("hidden", hidden ? now : null)
			.param("reason", hidden ? "Vi phạm quy tắc" : null).update();
	}

	/** Work Items on the Job's Board, each with its review actions in order. */
	private void reviews(UUID job, UUID product, List<List<String>> items) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO work.board (job_id, product_id, code, next_number, created_at) VALUES (:job, :product, 'SDB', :next, :now)
				""").param("job", job).param("product", product).param("next", items.size() + 1).param("now", now).update();
		for (int i = 0; i < items.size(); i++) {
			UUID item = UUID.randomUUID();
			this.jdbc.sql("""
					INSERT INTO work.work_item (id, job_id, number, type, title, created_at, updated_at)
					VALUES (:id, :job, :number, 'TASK', 'Kiểm tra', :now, :now)
					""").param("id", item).param("job", job).param("number", i + 1).param("now", now).update();
			for (String action : items.get(i)) {
				this.jdbc.sql("""
						INSERT INTO work.work_item_history (id, work_item_id, job_id, action, changes, at)
						VALUES (:id, :item, :job, :action, '{}'::jsonb, :now)
						""").param("id", UUID.randomUUID()).param("item", item).param("job", job).param("action", action)
					.param("now", now).update();
			}
		}
	}

	private void teamIssue(UUID job, UUID product, Instant escalated, String ending) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO dispute.team_issue (id, job_id, product_id, subject, rating_id, reason, phase, answer_days,
				    escalation_days, raised_at, answer_due_at, escalated_at, ending, ended_at)
				VALUES (:id, :job, :product, 'RATING', :rating, 'Không công bằng', :phase, 3, 3, :now, :now, :escalated, :ending,
				    :ended)
				""").param("id", UUID.randomUUID()).param("job", job).param("product", product).param("rating", UUID.randomUUID())
			.param("phase", ending != null ? "ENDED" : escalated != null ? "ESCALATED" : "ANSWER").param("now", now)
			.param("escalated", escalated == null ? null : Timestamp.from(escalated)).param("ending", ending)
			.param("ended", ending == null ? null : now).update();
	}

	/** A Crew row of the member on the Job, activated when {@code activated} (AD-41). */
	private void crewRow(UUID job, UUID member, boolean activated) {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, :status, :signed, :activated, :now)
				""").param("id", UUID.randomUUID()).param("job", job).param("member", member)
			.param("status", activated ? "ACTIVE" : "ASSIGNED").param("signed", activated ? now : null)
			.param("activated", activated ? now : null).param("now", now).update();
	}

	private RequestPostProcessor admin(Administrator administrator) {
		return IdentityTestSupport.signedIn(this.sessions, administrator.accountId(), Role.ADMINISTRATOR, this.clock.instant());
	}

}
