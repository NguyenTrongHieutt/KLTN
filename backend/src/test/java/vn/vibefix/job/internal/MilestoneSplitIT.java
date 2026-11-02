package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.authz.JobContent;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.job.MilestoneHandovers;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.job.internal.SplitStore.Party;
import vn.vibefix.payment.Releases;
import vn.vibefix.shared.PersonalData;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.timers.internal.DeadlinePoller;

/**
 * Story 22.2 (FR-61, FR-63, FR-83, AD-7, AD-20, AD-29, A-09, R11, X4, X5, SM-8): a split drafted, edited and re-confirmed;
 * the default at the draft deadline; the Deadlines, which no freeze pauses and which carry on after the Job ends and on a
 * bare row; and who reads and does what, from the split's party rows.
 */
class MilestoneSplitIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private MilestoneHandovers handovers;

	@Autowired
	private Releases releases;

	@Autowired
	private MilestoneSplits splits;

	@Autowired
	private DeadlinePoller poller;

	@Autowired
	private PersonalData personalData;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SessionRepository<? extends Session> sessions;

	private HeldCrewJob world;

	private UUID splitId;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	// ---- The release ----------------------------------------------------------------------------

	@Test
	void aReleaseWithCrewPartiesAsksTheMentorToDraftAndTellsTheParties() throws Exception {
		accept();

		assertThat(splitStatus()).isEqualTo("AWAITING_DRAFT");
		assertThat(notified("job.split_draft_due")).containsExactly(this.world.hung());
		assertThat(notified("job.split_pending")).containsExactlyInAnyOrder(this.world.vy(), this.world.minh());
		assertThat(openTodos("job.draft_split")).containsExactly(this.world.hung());
		assertThat(text("SELECT role FROM notification.todo WHERE todo_key = 'job.draft_split'")).isEqualTo("MENTOR");
		assertThat(deadlines("job.split_draft")).hasSize(1);
		assertThat(deadlines("job.split_draft_reminder")).hasSize(1);
		assertThat(notified("job.split_draft_due")).doesNotContain(this.world.lan());
	}

	@Test
	void aDraftPeriodOfOneDayHasNoReminderAndAConfirmationPeriodOfOneDayNeither() throws Exception {
		this.jdbc.sql("""
				UPDATE job.job SET rules = rules || '{"SPLIT_DRAFT_DAYS": 1, "SPLIT_CONFIRMATION_DAYS": 1}'::jsonb WHERE id = :id
				""").param("id", this.world.job()).update();
		accept();
		assertThat(deadlines("job.split_draft_reminder")).isEmpty();

		save(0, 30, 40, 30).andExpect(status().isOk());

		assertThat(deadlines("job.split_confirmation")).hasSize(1);
		assertThat(deadlines("job.split_confirmation_reminder")).isEmpty();
	}

	// ---- Save ------------------------------------------------------------------------------------

	@Test
	void aDraftBeforeTheDueTimeGoesOutForConfirmationAndMovesNoVersion() throws Exception {
		accept();
		long version = number("SELECT version FROM job.job WHERE id = '" + this.world.job() + "'");

		save(0, 30, 40, 30).andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNo").value(1))
			.andExpect(jsonPath("$.status").value("PROPOSED"));

		assertThat(splitStatus()).isEqualTo("PROPOSED");
		assertThat(number("SELECT confirmation_round FROM job.milestone_split")).isOne();
		assertThat(text("SELECT kind || ':' || cap_percent || ':' || author_account_id FROM job.milestone_split_version"))
			.isEqualTo("DRAFT:30:" + this.world.hung());
		assertThat(this.jdbc.sql("SELECT percent FROM job.milestone_split_share ORDER BY percent").query(Integer.class).list())
			.containsExactly(30, 30, 40);
		assertThat(openTodos("job.draft_split")).isEmpty();
		assertThat(openTodos("job.confirm_split")).containsExactlyInAnyOrder(this.world.vy(), this.world.minh());
		assertThat(text("SELECT DISTINCT role FROM notification.todo WHERE todo_key = 'job.confirm_split'")).isEqualTo("CREW");
		assertThat(notified("job.split_proposed")).containsExactlyInAnyOrder(this.world.vy(), this.world.minh());
		assertThat(number("SELECT version FROM job.job WHERE id = '" + this.world.job() + "'")).isEqualTo(version);
		assertThat(number("SELECT crew_version FROM job.job WHERE id = '" + this.world.job() + "'")).isZero();
		assertThat(timestamp("SELECT confirmation_due_at FROM job.milestone_split"))
			.isEqualTo(this.clock.instant().plus(Duration.ofDays(3)));
		assertThat(audit("job.split_draft_saved", this.world.hung())).isOne();
	}

	@Test
	void aBadDraftWritesNothing() throws Exception {
		accept();
		Map<String, String> bodies = Map.of("Mentor above the cap", shares(31, 39, 30), "sum 99", shares(30, 39, 30),
				"a member of the whole Milestone at 0", shares(30, 70, 0), "a fraction", """
						{"shownVersionNo":0,"shares":[{"partyId":"%s","percent":30},{"partyId":"%s","percent":33.5},
						 {"partyId":"%s","percent":36.5}]}
						""".formatted(party(this.world.hung()), party(this.world.vy()), party(this.world.minh())),
				"an extra party", """
						{"shownVersionNo":0,"shares":[{"partyId":"%s","percent":30},{"partyId":"%s","percent":30},
						 {"partyId":"%s","percent":30},{"partyId":"%s","percent":10}]}
						""".formatted(party(this.world.hung()), party(this.world.vy()), party(this.world.minh()), UUID.randomUUID()),
				"a party left out", """
						{"shownVersionNo":0,"shares":[{"partyId":"%s","percent":30},{"partyId":"%s","percent":70}]}
						""".formatted(party(this.world.hung()), party(this.world.vy())),
				"a party twice", """
						{"shownVersionNo":0,"shares":[{"partyId":"%s","percent":30},{"partyId":"%s","percent":30},
						 {"partyId":"%s","percent":40}]}
						""".formatted(party(this.world.hung()), party(this.world.vy()), party(this.world.vy())));

		for (Map.Entry<String, String> bad : bodies.entrySet()) {
			send("/versions", this.world.hung(), bad.getValue()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"))
				.andExpect(jsonPath("$.reason").value("SPLIT_INVALID"));
		}

		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isZero();
		assertThat(splitStatus()).isEqualTo("AWAITING_DRAFT");
	}

	@Test
	void withNoPartyActiveAtTheReleaseTheMentorIsUncappedAndLeaversMayGetNothing() throws Exception {
		for (UUID member : List.of(this.world.vy(), this.world.minh())) {
			leave(member, this.clock.instant().minus(Duration.ofHours(1)));
		}
		accept();

		save(0, 100, 0, 0).andExpect(status().isOk());

		assertThat(number("SELECT count(*) FROM job.milestone_split_version WHERE cap_percent IS NULL")).isOne();
		cards(this.world.hung()).andExpect(jsonPath("$[0].capApplies").value(false))
			.andExpect(jsonPath("$[0].cap").doesNotExist());
	}

	@Test
	void aFirstDraftAtTheDueTimeIsLate() throws Exception {
		accept();
		this.clock.advance(Duration.ofDays(7));

		save(0, 30, 40, 30).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("SPLIT_DRAFT_LATE"));

		assertThat(splitStatus()).isEqualTo("AWAITING_DRAFT");
	}

	/** SM-8: a split edited and re-confirmed. */
	@Test
	void anEditStartsANewRoundAndConfirmationsStayOnTheirVersion() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		this.clock.advance(Duration.ofHours(1));

		save(1, 30, 35, 35).andExpect(status().isOk()).andExpect(jsonPath("$.versionNo").value(2));

		assertThat(number("SELECT confirmation_round FROM job.milestone_split")).isEqualTo(2);
		assertThat(timestamp("SELECT confirmation_due_at FROM job.milestone_split"))
			.isEqualTo(this.clock.instant().plus(Duration.ofDays(3)));
		assertThat(number("SELECT count(*) FROM job.milestone_split_confirmation WHERE version_no = 1")).isOne();
		assertThat(number("SELECT count(*) FROM job.milestone_split_confirmation WHERE version_no = 2")).isZero();
		assertThat(openTodos("job.confirm_split")).containsExactlyInAnyOrder(this.world.vy(), this.world.minh());
		assertThat(notified("job.split_edited")).containsExactlyInAnyOrder(this.world.vy(), this.world.minh());
		assertThat(deadlines("job.split_confirmation")).hasSize(2);

		confirm(this.world.vy(), 2).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROPOSED"));
		confirm(this.world.minh(), 2).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINAL"));

		assertThat(text("SELECT finality FROM job.milestone_split")).isEqualTo("CONFIRMED");
		assertThat(number("SELECT final_version_no FROM job.milestone_split")).isEqualTo(2);
		// The old round's Deadline ends OBSOLETE, however late it runs.
		this.clock.advance(Duration.ofDays(4));
		this.poller.runDue();
		assertThat(deadlineStatuses("job.split_confirmation")).containsExactly("OBSOLETE", "OBSOLETE");
	}

	@Test
	void theFourthEditIsRefusedByTheLiveLimit() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		for (int shown = 1; shown <= 3; shown++) {
			save(shown, 30, 35, 35).andExpect(status().isOk());
		}

		save(4, 30, 35, 35).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_EDIT_LIMIT"))
			.andExpect(jsonPath("$.n").value(3));

		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isEqualTo(4);
		// ADM-10 raises it: the next save reads the new value.
		rule("SPLIT_EDIT_LIMIT", 4);
		save(4, 30, 35, 35).andExpect(status().isOk());
		cards(this.world.hung()).andExpect(jsonPath("$[0].editsLeft").value(0)).andExpect(jsonPath("$[0].editLimit").value(4));
	}

	@Test
	void anOldVersionIsStaleOnASaveAndOnAConfirmation() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		save(1, 30, 35, 35).andExpect(status().isOk());

		save(1, 30, 40, 30).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));
		confirm(this.world.vy(), 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE"));

		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isEqualTo(2);
		assertThat(number("SELECT count(*) FROM job.milestone_split_confirmation")).isZero();
	}

	/** FR-61: a new cap applies at the next save and stays on the version; the days are the Job's copy. */
	@Test
	void aRuleChangeReachesTheNextSaveAndOnlyTheCapAndTheLimit() throws Exception {
		this.jdbc.sql("""
				UPDATE job.job SET rules = rules || '{"SPLIT_CONFIRMATION_DAYS": 5, "MENTOR_SHARE_CAP_PERCENT": 90}'::jsonb
				WHERE id = :id
				""").param("id", this.world.job()).update();
		accept();
		rule("MENTOR_SHARE_CAP_PERCENT", 20);
		rule("SPLIT_CONFIRMATION_DAYS", 9);

		save(0, 21, 40, 39).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SPLIT_INVALID"))
			.andExpect(jsonPath("$.cap").value(20));
		save(0, 20, 40, 40).andExpect(status().isOk());

		assertThat(number("SELECT cap_percent FROM job.milestone_split_version")).isEqualTo(20);
		assertThat(timestamp("SELECT confirmation_due_at FROM job.milestone_split"))
			.as("the Job's copied days").isEqualTo(this.clock.instant().plus(Duration.ofDays(5)));
		assertThat(this.splits.rulesOf(this.world.job()))
			.isEqualTo(new MilestoneSplits.SplitRules(20, 7, 5, 3));
	}

	// ---- Confirm ---------------------------------------------------------------------------------

	@Test
	void whoConfirmsAndWhoIsRefused() throws Exception {
		accept();
		confirm(this.world.vy(), 0).andExpect(status().isNotFound());
		save(0, 30, 40, 30).andExpect(status().isOk());

		confirm(this.world.hung(), 1).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("PARTY_ONLY"));
		confirm(this.world.lan(), 1).andExpect(status().isNotFound());
		confirm(stranger(), 1).andExpect(status().isNotFound());
		save(this.world.vy(), 1, 30, 40, 30).andExpect(status().isForbidden()).andExpect(jsonPath("$.reason").value("MENTOR_ONLY"));
		save(this.world.lan(), 1, 30, 40, 30).andExpect(status().isNotFound());

		confirm(this.world.vy(), 1).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());

		assertThat(number("SELECT count(*) FROM job.milestone_split_confirmation")).isOne();
		assertThat(notified("job.split_party_confirmed")).containsExactly(this.world.hung());
		assertThat(openTodos("job.confirm_split")).containsExactly(this.world.minh());
		assertThat(audit("job.split_confirmed", this.world.vy())).isOne();
		// The Mentor never has a row, and {k}/{n} counts the others.
		cards(this.world.hung()).andExpect(jsonPath("$[0].k").value(1)).andExpect(jsonPath("$[0].n").value(2));
	}

	@Test
	void whenEveryPartyConfirmedItIsFinalOnceAndThePoolReachesTheBalances() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());

		confirm(this.world.minh(), 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FINAL"));
		awaitListeners();

		assertThat(text("SELECT status || ':' || final_version_no || ':' || finality FROM job.milestone_split"))
			.isEqualTo("FINAL:1:CONFIRMED");
		assertThat(timestamp("SELECT final_at FROM job.milestone_split")).isEqualTo(this.clock.instant());
		assertThat(number("""
				SELECT count(*) FROM platform.event_publication WHERE listener_id LIKE '%SplitPoolExecution%'
				""")).as("one event").isOne();
		assertThat(this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' ORDER BY amount_vnd")
			.query(Long.class).list()).containsExactly(300_000L, 300_000L, 400_000L);
		assertThat(notified("job.split_final")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy(), this.world.minh());
		assertThat(notified("job.split_final")).doesNotContain(this.world.lan());
		assertThat(openTodos("job.confirm_split")).isEmpty();
		assertThat(audit("job.split_final_confirmed", null)).isOne();

		confirm(this.world.vy(), 1).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("SPLIT_FINAL"));
		save(1, 30, 35, 35).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("SPLIT_FINAL"));
		// Nothing of it ever reached the Customer (AD-20).
		String customer = "'" + this.world.lan() + "'";
		assertThat(number("SELECT count(*) FROM notification.notification WHERE type LIKE 'job.split%' AND account_id = " + customer))
			.isZero();
		assertThat(number("SELECT count(*) FROM notification.todo WHERE todo_key LIKE 'job.%_split' AND account_id = " + customer))
			.isZero();
	}

	@Test
	void aPartyAtZeroGetsNoFundsReleasedOnlyTheFinalNotice() throws Exception {
		leave(this.world.minh(), this.clock.instant().minus(Duration.ofHours(1)));
		accept();
		save(0, 30, 70, 0).andExpect(status().isOk());
		confirm(this.world.minh(), 1).andExpect(status().isOk());

		confirm(this.world.vy(), 1).andExpect(status().isOk());
		awaitListeners();

		assertThat(notified("payment.funds_released")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy());
		assertThat(notified("job.split_final")).contains(this.world.minh());
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(2);
	}

	// ---- Deadlines -------------------------------------------------------------------------------

	@Test
	void theConfirmationDeadlineFinalizesTheVersionHoweverLateItRuns() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(30));
		this.poller.runDue();
		awaitListeners();

		assertThat(text("SELECT status || ':' || finality FROM job.milestone_split")).isEqualTo("FINAL:DEADLINE");
		assertThat(deadlineStatuses("job.split_confirmation")).containsExactly("FIRED");
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")).isOne();
		assertThat(audit("job.split_confirmation_expired", null)).isOne();
		assertThat(audit("job.split_final_deadline", null)).isOne();
	}

	/** A-09: past the due time and before the poller, nothing supersedes the version. */
	@Test
	void pastTheDueTimeASaveAndAConfirmationAreRefusedAndTheOldVersionFinalizes() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(3));

		save(1, 30, 35, 35).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("GUARD_FAILED"))
			.andExpect(jsonPath("$.reason").value("SPLIT_FINAL"))
			.andExpect(jsonPath("$.pastDue").value(true));
		confirm(this.world.vy(), 1).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("SPLIT_FINAL"));
		cards(this.world.vy()).andExpect(jsonPath("$[0].pastDue").value(true));

		this.poller.runDue();

		assertThat(text("SELECT final_version_no || ':' || finality FROM job.milestone_split")).isEqualTo("1:DEADLINE");
		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isOne();
	}

	/** SM-8: the default at the draft deadline. */
	@Test
	void withNoDraftAtTheDeadlineTheDefaultRunsAFullRoundAndTheMentorCannotEditIt() throws Exception {
		accept();
		this.clock.advance(Duration.ofDays(7).plusHours(5));

		this.poller.runDue();

		assertThat(text("SELECT kind || ':' || cap_percent FROM job.milestone_split_version")).isEqualTo("DEFAULT:30");
		assertThat(number("SELECT count(*) FROM job.milestone_split_version WHERE author_account_id IS NULL")).isOne();
		assertThat(splitStatus()).isEqualTo("PROPOSED");
		assertThat(this.jdbc.sql("""
				SELECT h.percent FROM job.milestone_split_share h JOIN job.milestone_split_party p ON p.id = h.party_id
				ORDER BY p.position
				""").query(Integer.class).list()).containsExactly(30, 35, 35);
		assertThat(timestamp("SELECT confirmation_due_at FROM job.milestone_split"))
			.as("from when it actually saved").isEqualTo(this.clock.instant().plus(Duration.ofDays(3)));
		assertThat(notified("job.split_defaulted")).containsExactlyInAnyOrder(this.world.hung(), this.world.vy(), this.world.minh());
		assertThat(openTodos("job.draft_split")).isEmpty();
		assertThat(openTodos("job.confirm_split")).containsExactlyInAnyOrder(this.world.vy(), this.world.minh());
		assertThat(audit("job.split_default_saved", null)).isOne();
		assertThat(audit("job.split_draft_expired", null)).isOne();

		save(1, 30, 40, 30).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("SPLIT_DRAFT_LATE"));
		save(0, 30, 40, 30).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_DRAFT_LATE"));

		confirm(this.world.vy(), 1).andExpect(status().isOk());
		confirm(this.world.minh(), 1).andExpect(jsonPath("$.status").value("FINAL"));
	}

	@Test
	void aDraftLeavesTheDraftDeadlineAndItsReminderObsolete() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		confirm(this.world.minh(), 1).andExpect(status().isOk());

		this.clock.advance(Duration.ofDays(8));
		this.poller.runDue();

		assertThat(deadlineStatuses("job.split_draft")).containsExactly("OBSOLETE");
		assertThat(deadlineStatuses("job.split_draft_reminder")).containsExactly("OBSOLETE");
		assertThat(notified("job.split_draft_reminder")).isEmpty();
		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).isOne();
	}

	@Test
	void theRemindersReachTheMentorADayBeforeAndThoseWhoHaveNotConfirmed() throws Exception {
		accept();
		this.clock.advance(Duration.ofDays(6));
		this.poller.runDue();
		assertThat(notified("job.split_draft_reminder")).containsExactly(this.world.hung());

		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		this.clock.advance(Duration.ofDays(2));
		this.poller.runDue();

		assertThat(notified("job.split_confirmation_reminder")).containsExactly(this.world.minh());
	}

	/** Set by SQL until Story 22.3: nobody confirms, and the Deadline ends without finalizing. */
	@Test
	void anOpenResolutionRefusesConfirmationsAndLeavesTheDeadlineObsolete() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		this.jdbc.sql("UPDATE job.milestone_split SET status = 'IN_RESOLUTION'").update();

		confirm(this.world.vy(), 1).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_IN_RESOLUTION"));
		save(1, 30, 35, 35).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.reason").value("SPLIT_IN_RESOLUTION"));

		this.clock.advance(Duration.ofDays(4));
		this.poller.runDue();

		assertThat(splitStatus()).isEqualTo("IN_RESOLUTION");
		assertThat(deadlineStatuses("job.split_confirmation")).containsExactly("OBSOLETE");
		assertThat(deadlineStatuses("job.split_confirmation_reminder")).containsExactly("OBSOLETE");
	}

	// ---- Independence (AD-29) --------------------------------------------------------------------

	@Test
	void aFrozenJobPausesNothingOfASplit() throws Exception {
		accept();
		Timestamp frozen = Timestamp.from(this.clock.instant());
		this.jdbc.sql("UPDATE job.job SET frozen_at = :at WHERE id = :id").param("at", frozen).param("id", this.world.job()).update();
		this.jdbc.sql("INSERT INTO timers.frozen_job (job_id, frozen_at) VALUES (:job, :at)")
			.param("job", this.world.job())
			.param("at", frozen)
			.update();
		this.jdbc.sql("""
				UPDATE job.job SET busy_operation_key = 'test:busy', busy_purpose = 'ACCEPTANCE', busy_subject = 'test', busy_since = :at
				WHERE id = :id
				""").param("at", frozen).param("id", this.world.job()).update();

		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		assertThat(this.jdbc.sql("SELECT DISTINCT pausable FROM timers.deadline WHERE kind LIKE 'job.split%'").query(Boolean.class).list())
			.containsExactly(false);

		this.clock.advance(Duration.ofDays(3));
		this.poller.runDue();

		assertThat(text("SELECT status || ':' || finality FROM job.milestone_split")).isEqualTo("FINAL:DEADLINE");
	}

	/** J9 and J10: a split continues after the Job ends. */
	@ParameterizedTest
	@ValueSource(strings = { "CLOSED", "CANCELLED" })
	void aLeaverReadsTheSplitAndConfirmsAlsoAfterTheJobEnded(String ended) throws Exception {
		leave(this.world.minh(), this.clock.instant().minus(Duration.ofHours(1)));
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		this.jdbc.sql("UPDATE job.job SET state = :state WHERE id = :id").param("state", ended).param("id", this.world.job()).update();

		cards(this.world.minh()).andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].viewerIsMentor").value(false))
			.andExpect(jsonPath("$[0].viewerConfirmed").value(false))
			.andExpect(jsonPath("$[0].latest.lines", hasSize(3)))
			.andExpect(jsonPath("$[0].parties[2].leftAt").exists());

		confirm(this.world.minh(), 1).andExpect(status().isOk());
		cards(this.world.minh()).andExpect(jsonPath("$[0].viewerConfirmed").value(true))
			.andExpect(jsonPath("$[0].latest.lines[2].confirmed").value(true));
	}

	@Test
	void whoReadsTheCards() throws Exception {
		cards(this.world.hung()).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
		cards(this.world.vy()).andExpect(status().isNotFound());
		cards(this.world.lan()).andExpect(status().isNotFound());
		accept();

		cards(this.world.hung()).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("AWAITING_DRAFT"))
			.andExpect(jsonPath("$[0].pooledVnd").value(HeldCrewJob.PRICE))
			.andExpect(jsonPath("$[0].paid").value(false))
			.andExpect(jsonPath("$[0].cap").value(30))
			.andExpect(jsonPath("$[0].editsLeft").value(3))
			.andExpect(jsonPath("$[0].viewerIsMentor").value(true))
			.andExpect(jsonPath("$[0].latest").doesNotExist());
		cards(this.world.vy()).andExpect(status().isOk());
		cards(this.world.lan()).andExpect(status().isNotFound());
		cards(stranger()).andExpect(status().isNotFound());
		String answer = cards(this.world.hung()).andReturn().getResponse().getContentAsString();
		assertThat(answer).doesNotContain("RELEASE_POOLED", "POOL_RELEASED");
	}

	@Test
	void aPartyOfOneMilestoneReadsNothingOfAnothersSplit() throws Exception {
		UUID other = this.world.split(this.jdbc, this.clock.instant(), UUID.randomUUID(), this.world.vy());
		accept();

		String minh = cards(this.world.minh()).andExpect(jsonPath("$", hasSize(1))).andReturn().getResponse().getContentAsString();
		assertThat(minh).doesNotContain(other.toString());
		cards(this.world.vy()).andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void everyVersionStaysReadableWithItsLines() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		save(1, 30, 35, 35).andExpect(status().isOk());
		confirm(this.world.vy(), 2).andExpect(status().isOk());
		confirm(this.world.minh(), 2).andExpect(status().isOk());
		awaitListeners();

		cards(this.world.minh()).andExpect(jsonPath("$[0].status").value("FINAL"))
			.andExpect(jsonPath("$[0].finality").value("CONFIRMED"))
			.andExpect(jsonPath("$[0].paid").value(true))
			.andExpect(jsonPath("$[0].latest.versionNo").value(2))
			.andExpect(jsonPath("$[0].latest.lines[1].amountVnd").value(350_000))
			.andExpect(jsonPath("$[0].earlier", hasSize(1)))
			.andExpect(jsonPath("$[0].earlier[0].versionNo").value(1))
			.andExpect(jsonPath("$[0].earlier[0].lines[1].percent").value(40))
			.andExpect(jsonPath("$[0].earlier[0].lines[1].amountVnd").value(400_000));
	}

	/** AD-33: the VND comes from {@code CrewSplit} on the pool, the remainder to the Mentor. */
	@Test
	void thePreviewIsTheMentorsAndComesFromTheServer() throws Exception {
		this.jdbc.sql("UPDATE job.milestone SET price_vnd = 10000001 WHERE id = :id").param("id", this.world.milestone()).update();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, bucket, job_id, product_id, milestone_id,
				    payment_order_id, amount_vnd, vnp_transaction_no, created_at)
				SELECT :entry, :key, 'FUND', 'MILESTONE', job_id, product_id, milestone_id, id, 9000001, :no, now()
				FROM payment.payment_order WHERE milestone_id = :id
				""")
			.param("entry", UUID.randomUUID())
			.param("key", "FUND:" + UUID.randomUUID())
			.param("no", UUID.randomUUID().toString())
			.param("id", this.world.milestone())
			.update();
		accept();

		send("/preview", this.world.hung(), shares(30, 50, 20)).andExpect(status().isOk())
			.andExpect(jsonPath("$.pooledVnd").value(10_000_001))
			.andExpect(jsonPath("$.lines[0].partyId").value(party(this.world.hung()).toString()))
			.andExpect(jsonPath("$.lines[0].amountVnd").value(3_000_001))
			.andExpect(jsonPath("$.lines[1].amountVnd").value(5_000_000))
			.andExpect(jsonPath("$.lines[2].amountVnd").value(2_000_000));

		send("/preview", this.world.hung(), shares(31, 49, 20)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reason").value("SPLIT_INVALID"));
		send("/preview", this.world.vy(), shares(30, 50, 20)).andExpect(status().isForbidden());
		send("/preview", this.world.lan(), shares(30, 50, 20)).andExpect(status().isNotFound());
		assertThat(number("SELECT count(*) FROM job.milestone_split_version")).as("a preview saves nothing").isZero();
	}

	/** R11's figures (FR-83), the spec's matrix. */
	@Test
	void theDefaultFigures() {
		assertThat(SplitCommands.defaultPercents(parties(3), 30).values()).containsExactly(30, 35, 35);
		assertThat(SplitCommands.defaultPercents(parties(2), 30).values()).containsExactly(30, 70);
		assertThat(SplitCommands.defaultPercents(parties(7), 30).values()).containsExactly(14, 15, 15, 14, 14, 14, 14);
		assertThat(SplitCommands.defaultPercents(parties(3), null).values()).containsExactly(33, 34, 33);
	}

	@Test
	void aLockedMentorsDraftDeadlineYieldsTheDefault() throws Exception {
		accept();
		this.jdbc.sql("UPDATE identity.account SET locked_at = :at, lock_reason = 'Vi phạm' WHERE id = :id")
			.param("at", Timestamp.from(this.clock.instant()))
			.param("id", this.world.hung())
			.update();

		this.clock.advance(Duration.ofDays(7));
		this.poller.runDue();

		assertThat(text("SELECT kind FROM job.milestone_split_version")).isEqualTo("DEFAULT");
		save(1, 30, 40, 30).andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(400, 499));
	}

	@Test
	void aPartyErasedAfterTheSplitIsFinalLeavesThePercentagesAndNoPerson() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		confirm(this.world.minh(), 1).andExpect(status().isOk());
		awaitListeners();
		closeJob();

		this.transactions.executeWithoutResult(status -> this.personalData.erase(this.world.vy()));

		assertThat(number("SELECT count(*) FROM job.milestone_split_party WHERE party_account_id IS NULL")).isOne();
		assertThat(number("SELECT count(*) FROM job.milestone_split_confirmation")).isEqualTo(2);
		cards(this.world.minh()).andExpect(jsonPath("$[0].latest.lines[1].percent").value(40))
			.andExpect(jsonPath("$[0].parties[1].name").doesNotExist());
	}

	// ---- A bare row (AD-24) ----------------------------------------------------------------------

	@Test
	void onABareRowTheDeadlinesSavesAndConfirmationsStillWork() throws Exception {
		accept();
		closeJob();
		JobContent.chat(this.jdbc, this.world.job(), this.world.product(), "CREW");
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));
		assertThat(number("SELECT count(*) FROM job.job WHERE product_deleted_at IS NOT NULL")).isOne();
		assertThat(number("SELECT count(*) FROM chat.chat")).as("the evidence waits for the split").isOne();

		cards(this.world.vy()).andExpect(status().isOk()).andExpect(jsonPath("$[0].jobTitle").value("Sửa lỗi đặt bàn"));
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		confirm(this.world.minh(), 1).andExpect(jsonPath("$.status").value("FINAL"));
		awaitListeners();

		assertThat(number("SELECT count(*) FROM job.job")).as("the bare row has done its work").isZero();
		assertThat(number("SELECT count(*) FROM chat.chat")).as("and the evidence finishes").isZero();
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE'")).isEqualTo(3);
		cards(this.world.vy()).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("FINAL"))
			.andExpect(jsonPath("$[0].jobTitle").doesNotExist());
		confirm(this.world.vy(), 1).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("SPLIT_FINAL"));
	}

	@Test
	void onABareRowTheDraftDeadlineDefaultsAndTheConfirmationDeadlineFinalizes() throws Exception {
		accept();
		closeJob();
		this.transactions.executeWithoutResult(status -> this.personalData.deleteProduct(this.world.product()));

		this.clock.advance(Duration.ofDays(7));
		this.poller.runDue();
		assertThat(text("SELECT kind FROM job.milestone_split_version")).isEqualTo("DEFAULT");

		this.clock.advance(Duration.ofDays(3));
		this.poller.runDue();
		awaitListeners();

		assertThat(text("SELECT status || ':' || finality FROM job.milestone_split")).isEqualTo("FINAL:DEADLINE");
		assertThat(number("SELECT count(*) FROM job.job")).isZero();
	}

	/** The lock upgrade (22.1's deferred entry): a finalization queued behind a deletion waits, then finds the bare row. */
	@Test
	void aFinalizationQueuedBehindADeletionWaitsAndThenFindsTheBareRow() throws Exception {
		accept();
		save(0, 30, 40, 30).andExpect(status().isOk());
		confirm(this.world.vy(), 1).andExpect(status().isOk());
		closeJob();
		RequestPostProcessor minh = as(this.world.minh());
		String body = "{\"splitVersionNo\":1}";
		AtomicReference<CompletableFuture<Integer>> confirmation = new AtomicReference<>();

		this.transactions.executeWithoutResult(status -> {
			assertThat(this.splits.openSplitJobs(this.world.product())).containsExactly(this.world.job());
			confirmation.set(CompletableFuture.supplyAsync(() -> {
				try {
					return this.mvc.perform(post("/api/v1/splits/" + split() + "/confirmations").with(minh)
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body)).andReturn().getResponse().getStatus();
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}));
			await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> !confirmation.get().isDone());
			this.personalData.deleteProduct(this.world.product());
		});

		assertThat(confirmation.get().get(15, TimeUnit.SECONDS)).isEqualTo(200);
		assertThat(text("SELECT status || ':' || finality FROM job.milestone_split")).isEqualTo("FINAL:CONFIRMED");
		awaitListeners();
		assertThat(number("SELECT count(*) FROM job.job")).isZero();
		assertThat(number("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'POOL_RELEASED'")).isOne();
	}

	// ---- Helpers ---------------------------------------------------------------------------------

	/** M5 as {@code delivery} applies it on an Assessment Job, then the pool, in one transaction. */
	private void accept() {
		this.transactions.executeWithoutResult(status -> {
			UUID handover = UUID.randomUUID();
			this.jdbc.sql("UPDATE job.milestone SET handover_id = :handover, state = 'SUBMITTED', submitted_at = :now WHERE id = :id")
				.param("handover", handover)
				.param("now", Timestamp.from(this.clock.instant()))
				.param("id", this.world.milestone())
				.update();
			UUID transition = this.handovers.autoAccept(this.world.job(), this.world.milestone(), handover).orElseThrow().transitionId();
			this.releases.release(this.world.job(), this.world.milestone(), transition);
		});
		this.splitId = this.jdbc.sql("SELECT id FROM job.milestone_split WHERE milestone_id = :id")
			.param("id", this.world.milestone())
			.query(UUID.class)
			.single();
	}

	private void closeJob() {
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("UPDATE job.job SET state = 'CLOSED', closed_at = :now").param("now", now).update();
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'JOB_ENDED', released_at = :now, window_end = :now
				""").param("now", now).update();
	}

	private void leave(UUID member, Instant end) {
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :end, window_end = :end
				WHERE job_id = :job AND member_account_id = :member
				""").param("end", Timestamp.from(end)).param("job", this.world.job()).param("member", member).update();
	}

	private void rule(String key, int value) {
		this.jdbc.sql("UPDATE settings.rule_value SET value = :value WHERE rule_key = :key").param("value", value).param("key", key).update();
	}

	private UUID split() {
		return this.splitId;
	}

	private UUID party(UUID account) {
		return this.jdbc.sql("SELECT id FROM job.milestone_split_party WHERE split_id = :split AND party_account_id = :account")
			.param("split", split())
			.param("account", account)
			.query(UUID.class)
			.single();
	}

	private static List<Party> parties(int count) {
		return java.util.stream.IntStream.range(0, count)
			.mapToObj(position -> new Party(UUID.randomUUID(), UUID.randomUUID(), position == 0, true, true))
			.toList();
	}

	private String shares(int mentor, int vy, int minh) {
		return shares(0, mentor, vy, minh);
	}

	private String shares(int shown, int mentor, int vy, int minh) {
		return """
				{"shownVersionNo":%d,"shares":[{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d},{"partyId":"%s","percent":%d}]}
				""".formatted(shown, party(this.world.hung()), mentor, party(this.world.vy()), vy, party(this.world.minh()), minh);
	}

	private ResultActions save(int shown, int mentor, int vy, int minh) throws Exception {
		return save(this.world.hung(), shown, mentor, vy, minh);
	}

	private ResultActions save(UUID account, int shown, int mentor, int vy, int minh) throws Exception {
		return send("/versions", account, shares(shown, mentor, vy, minh));
	}

	private ResultActions confirm(UUID account, int version) throws Exception {
		return send("/confirmations", account, "{\"splitVersionNo\":" + version + "}");
	}

	private ResultActions send(String path, UUID account, String body) throws Exception {
		return this.mvc.perform(post("/api/v1/splits/" + split() + path)
			.with(as(account))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions cards(UUID account) throws Exception {
		return this.mvc.perform(get("/api/v1/jobs/" + this.world.job() + "/splits").with(as(account)));
	}

	private RequestPostProcessor as(UUID account) {
		return IdentityTestSupport.signedIn(this.sessions, account, null, this.clock.instant());
	}

	private UUID stranger() {
		return IdentityTestSupport.insertGoogleAccount(this.jdbc, "google-split-" + UUID.randomUUID(), "3.1");
	}

	private String splitStatus() {
		return text("SELECT status FROM job.milestone_split");
	}

	private List<UUID> notified(String type) {
		return this.jdbc.sql("SELECT account_id FROM notification.notification WHERE type = :type")
			.param("type", type)
			.query(UUID.class)
			.list();
	}

	private List<UUID> openTodos(String key) {
		return this.jdbc.sql("SELECT account_id FROM notification.todo WHERE todo_key = :key AND closed_at IS NULL")
			.param("key", key)
			.query(UUID.class)
			.list();
	}

	private List<UUID> deadlines(String kind) {
		return this.jdbc.sql("SELECT id FROM timers.deadline WHERE kind = :kind").param("kind", kind).query(UUID.class).list();
	}

	private List<String> deadlineStatuses(String kind) {
		return this.jdbc.sql("SELECT status FROM timers.deadline WHERE kind = :kind ORDER BY created_at, id")
			.param("kind", kind)
			.query(String.class)
			.list();
	}

	private long audit(String action, UUID actor) {
		return this.jdbc.sql("""
				SELECT count(*) FROM audit.audit_entry
				WHERE action = :action AND job_id = :job AND actor_account_id IS NOT DISTINCT FROM CAST(:actor AS UUID)
				""")
			.param("action", action)
			.param("job", this.world.job())
			.param("actor", actor, java.sql.Types.OTHER)
			.query(Long.class)
			.single();
	}

	private void awaitListeners() {
		await().atMost(Duration.ofSeconds(15)).until(() -> number("""
				SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL
				""") == 0);
	}

	private long number(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private String text(String sql) {
		return this.jdbc.sql(sql).query(String.class).single();
	}

	private Instant timestamp(String sql) {
		return this.jdbc.sql(sql).query(Timestamp.class).single().toInstant();
	}

}
