package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.payment.Releases;
import vn.vibefix.payment.ShareHolds;
import vn.vibefix.payment.ShareHolds.Transfer;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * Story 12.1 as Story 22.1 retires it from the money (AD-40, AD-15, SG-23): {@code payment.holdShares} holds only a
 * party of the pin on a Milestone with nothing released or pooled; a release reads no hold and no transfer; an ending
 * records its transfer and writes nothing to the ledger; and until Story 22.4 narrows it, the daily check still lists
 * held parts left unreversed and a hold left on a Milestone that became final without a release.
 */
class ShareHoldIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ShareHolds holds;

	@Autowired
	private Releases releases;

	@Autowired
	private LedgerCheck check;

	@Autowired
	private TransactionTemplate transactions;

	private HeldCrewJob world;

	@BeforeEach
	void aHeldMilestoneOfACrewOfThree() {
		this.world = HeldCrewJob.create(this.jdbc, this.clock.instant());
	}

	@Test
	void aHoldIsTakenOnceAndRefusedOnceAPoolExists() {
		UUID issue = UUID.randomUUID();
		hold(this.world.milestone(), issue, this.world.vy());
		hold(this.world.milestone(), issue, this.world.vy());
		assertThat(count("SELECT count(*) FROM payment.share_hold WHERE milestone_id = '" + this.world.milestone() + "'"))
			.as("a second call changes nothing").isOne();
		assertThat(refusal(() -> hold(this.world.milestone(), UUID.randomUUID(), UUID.randomUUID())))
			.as("not a party of the pin").isEqualTo("MILESTONE_NOT_HELD");

		release();
		assertThat(refusal(() -> hold(this.world.milestone(), UUID.randomUUID(), this.world.minh())))
			.as("pooled already").isEqualTo("MILESTONE_NOT_HELD");
	}

	@Test
	void aReleaseReadsNoHoldAndNoTransfer() {
		UUID ended = UUID.randomUUID();
		hold(this.world.milestone(), ended, this.world.vy());
		this.transactions.executeWithoutResult(status -> this.holds.endHold(ended, 10));
		hold(this.world.milestone(), UUID.randomUUID(), this.world.minh());
		assertThat(this.holds.transfersOf(this.world.milestone())).containsExactly(new Transfer(ended, this.world.vy(), 10));
		assertThat(this.holds.basisOf(this.world.milestone())).as("what is releasable now").isEqualTo(HeldCrewJob.PRICE);

		release();

		assertThat(count("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE_POOLED'")).isEqualTo(HeldCrewJob.PRICE);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type IN ('RELEASE', 'RELEASE_HELD')")).isZero();
		assertThat(count("SELECT count(*) FROM notification.notification WHERE type = 'payment.share_held'")).isZero();
		assertThat(this.holds.basisOf(this.world.milestone())).as("the pool").isEqualTo(HeldCrewJob.PRICE);
	}

	@Test
	void anEndingIsRefusedWholeAboveTheMentorsShareAndNeverWritesToTheLedger() {
		UUID issue = UUID.randomUUID();
		hold(this.world.milestone(), issue, this.world.vy());
		release();
		long entries = count("SELECT count(*) FROM payment.ledger_entry");

		assertThat(catchThrowableOfType(IllegalStateException.class,
				() -> this.transactions.executeWithoutResult(status -> this.holds.endHold(issue, 30))))
			.hasMessageContaining("SHARE_TRANSFER_TOO_LARGE");
		assertThat(count("SELECT count(*) FROM payment.share_hold WHERE team_issue_id = '" + issue + "' AND ended_at IS NULL"))
			.as("nothing is written").isOne();

		this.transactions.executeWithoutResult(status -> this.holds.endHold(issue, 15));
		this.transactions.executeWithoutResult(status -> this.holds.endHold(issue, 0));
		assertThat(count("SELECT transfer_points FROM payment.share_hold WHERE team_issue_id = '" + issue + "'"))
			.as("a second call changes nothing").isEqualTo(15);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry")).isEqualTo(entries);
	}

	@Test
	void theDailyCheckStillListsTheOldHoldCases() {
		UUID issue = UUID.randomUUID();
		hold(this.world.milestone(), issue, this.world.vy());
		UUID held = heldEntry(issue);
		assertThat(this.check.check()).as("the hold is open").doesNotContain(held);

		// The hold ends without reversing the held part: it is listed.
		this.jdbc.sql("UPDATE payment.share_hold SET ended_at = now(), transfer_points = 0 WHERE team_issue_id = :id")
			.param("id", issue)
			.update();
		assertThat(this.check.check()).contains(held);

		HeldCrewJob other = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID moot = UUID.randomUUID();
		hold(other.milestone(), moot, other.minh());
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", other.milestone()).update();
		assertThat(this.check.check()).as("a hold left on a final Milestone without a release").contains(moot);
	}

	@Test
	void aHoldLeftOpenOnAMilestoneWithAPoolIsNotListed() {
		UUID issue = UUID.randomUUID();
		hold(this.world.milestone(), issue, this.world.vy());
		release();

		this.jdbc.sql("UPDATE job.milestone SET state = 'ACCEPTED', accepted_at = now() WHERE id = :id").param("id", this.world.milestone()).update();
		assertThat(this.check.check()).as("ACCEPTED").doesNotContain(issue);
		this.jdbc.sql("UPDATE job.milestone SET state = 'SETTLED', accepted_at = NULL, settled_at = now() WHERE id = :id")
			.param("id", this.world.milestone())
			.update();
		assertThat(this.check.check()).as("SETTLED").doesNotContain(issue);
	}

	/** An old RELEASE_HELD of the Milestone, from before Story 22.1. */
	private UUID heldEntry(UUID issue) {
		UUID id = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO payment.ledger_entry (id, operation_key, entry_type, job_id, product_id, milestone_id, amount_vnd,
				    party_account_id, hold_id, created_at)
				VALUES (:id, :key, 'RELEASE_HELD', :job, :product, :milestone, 400000, :party, :hold, now())
				""")
			.param("id", id)
			.param("key", "RELEASE_HELD:" + id)
			.param("job", this.world.job())
			.param("product", this.world.product())
			.param("milestone", this.world.milestone())
			.param("party", this.world.vy())
			.param("hold", issue)
			.update();
		return id;
	}

	private void release() {
		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
	}

	private void hold(UUID milestone, UUID issue, UUID raiser) {
		this.transactions.executeWithoutResult(status -> this.holds.holdShares(milestone, issue, raiser));
	}

	private static String refusal(Runnable command) {
		return catchThrowableOfType(ApiException.class, command::run).reason().orElse("");
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

}
