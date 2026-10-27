package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.IntegrationTest;

/**
 * FR-44, AD-20, AD-43, Story 20.3: any unlocked user opens their Balance, which never needs an account role; the
 * Crew's parts of a release are shown only on the Jobs whose Mentor the reader is, by their role in each Job, so a
 * Mentor of one Job who is a Crew member of another reads that other Job's parts as a Crew member does: none.
 */
class BalanceRolesIT extends IntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	@Test
	void aMentorOfOneJobAndCrewMemberOfAnotherReadsTheCrewPartsOfTheirOwnJobOnly() throws Exception {
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		UUID hung = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-hung", "3.1");
		UUID thao = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-thao", "3.1");
		UUID vy = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-vy", "3.1");
		Instant now = this.clock.instant();
		// Job A: Hung mentors, Vy is in the Crew. Job B: Thao mentors, Hung is in the Crew.
		UUID milestoneA = job(lan, hung, vy);
		UUID milestoneB = job(lan, thao, hung);
		release(milestoneA, hung, 1_000_000, 20, now);
		release(milestoneA, vy, 4_000_000, 80, now);
		release(milestoneB, thao, 2_000_000, 40, now.plus(Duration.ofMinutes(1)));
		release(milestoneB, hung, 3_000_000, 60, now.plus(Duration.ofMinutes(1)));

		String body = this.mvc.perform(get("/api/v1/balance").with(as(hung))).andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(4_000_000))
			.andExpect(jsonPath("$.releases").value(2))
			.andExpect(jsonPath("$.lines[?(@.yourPartVnd == 1000000)].crew[*].amountVnd").value(4_000_000))
			.andExpect(jsonPath("$.lines[?(@.yourPartVnd == 3000000)].crew[*]").isEmpty())
			.andExpect(jsonPath("$.sentToCrewVnd").value(4_000_000))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).as("Job B's Mentor part is not Hung's to read").doesNotContain("2000000");

		this.mvc.perform(get("/api/v1/balance").with(as(vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(4_000_000))
			.andExpect(jsonPath("$.lines[0].crew.length()").value(0))
			.andExpect(jsonPath("$.sentToCrewVnd").doesNotExist());
		this.mvc.perform(get("/api/v1/balance").with(as(lan))).andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.lines.length()").value(0));
	}

	@Test
	void theMentorOfAFundedJobWithNoReleaseYetHasMoneyHeldAndACrewMemberNeither() throws Exception {
		UUID lan = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-lan", "3.1");
		UUID thao = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-thao", "3.1");
		UUID vy = IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-vy", "3.1");
		fund(job(lan, thao, vy), 3_000_000);

		this.mvc.perform(get("/api/v1/balance").with(as(thao))).andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.heldVnd").value(3_000_000))
			.andExpect(jsonPath("$.sentToCrewVnd").value(0));
		this.mvc.perform(get("/api/v1/balance").with(as(vy))).andExpect(status().isOk())
			.andExpect(jsonPath("$.balanceVnd").value(0))
			.andExpect(jsonPath("$.heldVnd").doesNotExist())
			.andExpect(jsonPath("$.sentToCrewVnd").doesNotExist());
	}

	/** A Job with a confirmed Agreement and one Milestone in the ledger; returns the Milestone. */
	private UUID job(UUID customer, UUID mentor, UUID member) {
		UUID job = UUID.randomUUID();
		UUID product = UUID.randomUUID();
		UUID milestone = UUID.randomUUID();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				INSERT INTO job.job (id, product_id, customer_account_id, state, title, job_types, rules, selection_days,
				    mentor_account_id, selected_application_id, selected_at, agreement_version_no, version, created_at,
				    updated_at)
				VALUES (:id, :product, :customer, 'ACTIVE', 'Sửa lỗi đặt bàn', ARRAY['BUG_FIX'], '{}'::jsonb, 14, :mentor,
				    :application, :now, 1, 3, :now, :now)
				""").param("id", job).param("product", product).param("customer", customer).param("mentor", mentor)
			.param("application", UUID.randomUUID()).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :member, 'ACTIVE', :now, :now, :now)
				""").param("id", UUID.randomUUID()).param("job", job).param("member", member).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_account (milestone_id, job_id, product_id, created_at)
				VALUES (:milestone, :job, :product, :now)
				""").param("milestone", milestone).param("job", job).param("product", product).param("now", now).update();
		return milestone;
	}

	/** The Milestone itself, still to be handed over, and its Customer's FUND of {@code amount}. */
	private void fund(UUID milestone, long amount) {
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at)
				SELECT milestone_id, job_id, 0, 'Báo cáo', :amount, 1, now() FROM payment.ledger_account WHERE milestone_id = :id
				""").param("id", milestone).param("amount", amount).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    amount_vnd, vnp_transaction_no, created_at)
				SELECT :entry, :key, 'FUND', 'MILESTONE', job_id, product_id, milestone_id, :amount, :no, now()
				FROM payment.ledger_account WHERE milestone_id = :id
				""")
			.param("entry", UUID.randomUUID())
			.param("key", "FUND:" + UUID.randomUUID())
			.param("amount", amount)
			.param("no", "VNP" + milestone)
			.param("id", milestone)
			.update();
	}

	/** One party's RELEASE of the Milestone; a Milestone's parts share one release transition. */
	private void release(UUID milestone, UUID party, long amount, int percent, Instant at) {
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, crew_terms_version_no, share_percent, base_amount_vnd, created_at)
				SELECT :id, :key, 'RELEASE', job_id, product_id, milestone_id, :amount, :party, 1, :percent, 5000000, :at
				FROM payment.ledger_account WHERE milestone_id = :milestone
				""")
			.param("id", UUID.randomUUID())
			.param("key", "RELEASE:" + milestone + ":" + party)
			.param("amount", amount)
			.param("party", party)
			.param("percent", percent)
			.param("milestone", milestone)
			.param("at", Timestamp.from(at))
			.update();
	}

	private RequestPostProcessor as(UUID account) {
		return as(account, null);
	}

	private RequestPostProcessor as(UUID account, @Nullable Role role) {
		return IdentityTestSupport.signedIn(this.sessions, account, role, this.clock.instant());
	}

}
