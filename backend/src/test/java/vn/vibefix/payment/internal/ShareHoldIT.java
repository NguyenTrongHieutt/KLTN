package vn.vibefix.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
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
 * Story 12.1 (AD-40, AD-15, SG-23): {@code payment.holdShares} holds only a party of the pin on a Milestone
 * with nothing released; a release splits by the pin adjusted by the recorded transfers; and the daily
 * check lists held parts left unreversed and a hold left on a Milestone that became final without a release.
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
	void aHoldIsTakenOnceAndHeldPartsLeftBehindAreListed() {
		UUID issue = UUID.randomUUID();
		hold(this.world.milestone(), issue, this.world.vy());
		hold(this.world.milestone(), issue, this.world.vy());
		assertThat(count("SELECT count(*) FROM payment.share_hold WHERE milestone_id = '" + this.world.milestone() + "'"))
			.as("a second call changes nothing").isOne();
		assertThat(refusal(() -> hold(this.world.milestone(), UUID.randomUUID(), UUID.randomUUID())))
			.as("not a party of the pin").isEqualTo("MILESTONE_NOT_HELD");

		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
		List<UUID> held = this.jdbc.sql("SELECT id FROM payment.ledger_entry WHERE entry_type = 'RELEASE_HELD'")
			.query(UUID.class)
			.list();
		assertThat(held).hasSize(2);
		assertThat(this.check.check()).as("the hold is open").doesNotContainAnyElementsOf(held);
		assertThat(refusal(() -> hold(this.world.milestone(), UUID.randomUUID(), this.world.minh())))
			.as("released already").isEqualTo("MILESTONE_NOT_HELD");

		// The hold ends without reversing the held parts (a missed Story 12.2 ending): both are listed.
		this.jdbc.sql("UPDATE payment.share_hold SET ended_at = now(), transfer_points = 0 WHERE team_issue_id = :id")
			.param("id", issue)
			.update();
		assertThat(this.check.check()).containsAll(held);

		HeldCrewJob other = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID moot = UUID.randomUUID();
		hold(other.milestone(), moot, other.minh());
		this.jdbc.sql("UPDATE job.milestone SET state = 'CANCELLED' WHERE id = :id").param("id", other.milestone()).update();
		assertThat(this.check.check()).as("a hold left on a final Milestone without a release").contains(moot);
	}

	@Test
	void aReleaseSplitsByThePinAdjustedByTheRecordedTransfers() {
		UUID issue = UUID.randomUUID();
		hold(this.world.milestone(), issue, this.world.vy());
		this.jdbc.sql("UPDATE payment.share_hold SET ended_at = now(), transfer_points = 10 WHERE team_issue_id = :id")
			.param("id", issue)
			.update();
		assertThat(this.holds.transfersOf(this.world.milestone())).containsExactly(new Transfer(issue, this.world.vy(), 10));

		this.transactions.executeWithoutResult(
				status -> this.releases.release(this.world.job(), this.world.milestone(), UUID.randomUUID()));
		assertThat(release(this.world.hung())).isEqualTo(100_000);
		assertThat(release(this.world.vy())).isEqualTo(500_000);
		assertThat(release(this.world.minh())).isEqualTo(400_000);
		assertThat(this.jdbc.sql("SELECT share_percent FROM payment.ledger_entry WHERE party_account_id = :vy")
			.param("vy", this.world.vy()).query(Integer.class).single()).isEqualTo(50);
		assertThat(this.jdbc.sql("SELECT transfer_ids[1] FROM payment.ledger_entry WHERE party_account_id = :vy")
			.param("vy", this.world.vy()).query(UUID.class).single()).isEqualTo(issue);
		assertThat(count("SELECT count(*) FROM payment.ledger_entry WHERE entry_type = 'RELEASE_HELD'")).isZero();
	}

	private void hold(UUID milestone, UUID issue, UUID raiser) {
		this.transactions.executeWithoutResult(status -> this.holds.holdShares(milestone, issue, raiser));
	}

	private static String refusal(Runnable command) {
		return catchThrowableOfType(ApiException.class, command::run).reason().orElse("");
	}

	private long release(UUID party) {
		return this.jdbc.sql("SELECT amount_vnd FROM payment.ledger_entry WHERE entry_type = 'RELEASE' AND party_account_id = :party")
			.param("party", party).query(Long.class).single();
	}

	private long count(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

}
