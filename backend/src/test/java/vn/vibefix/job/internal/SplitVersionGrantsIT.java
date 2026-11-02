package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.authz.JobContent;
import vn.vibefix.job.MilestoneSplits;
import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.shared.ApiException;
import vn.vibefix.support.AdminReadGrantsContract;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-20, AD-8, AD-40: {@code job}'s Administrator read grants for a Milestone Split's versions meet the Story 1.3 contract: a
 * grant naming the split reads it, audited, and a Dispute's grant, or one naming another split, reads nothing of it.
 */
class SplitVersionGrantsIT extends IntegrationTest implements AdminReadGrantsContract {

	@Autowired
	private SplitVersionGrants grants;

	@Autowired
	private MilestoneSplits splits;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public AdminReadGrants grants() {
		return this.grants;
	}

	@Override
	public AdminReadScope scope() {
		return AdminReadScope.SPLIT_VERSIONS;
	}

	@Override
	public Seeded seed() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID[] split = JobContent.proposedSplit(this.jdbc, world.job(), world.product(), world.milestone(), 1, world.hung(), world.vy(),
				world.minh(), 30, 40, 30, this.clock.instant(), this.clock.instant().plus(Duration.ofDays(3)));
		return new Seeded(world.job(), AdminReadScope.SPLIT_VERSIONS, split[0]);
	}

	@Override
	public void read(UUID adminId, Seeded item) {
		this.splits.evidenceUnderGrant(adminId, item.objectId());
	}

	@Override
	public long auditedReads(UUID adminId) {
		return this.jdbc.sql("SELECT count(*) FROM audit.audit_entry WHERE action = 'job.admin_read' AND actor_account_id = :admin")
			.param("admin", adminId)
			.query(Long.class)
			.single();
	}

	@Override
	public Instant now() {
		return this.clock.instant();
	}

	@Override
	public void advance(Duration duration) {
		this.clock.advance(duration);
	}

	@Test
	void aDisputesGrantReadsNoSplitVersion() {
		Seeded item = seed();
		this.grants.grantAdminRead(UUID.randomUUID(), ADMIN, item.jobId(), AdminReadItem.forDispute(), REASON);

		assertThatThrownBy(() -> read(ADMIN, item)).isInstanceOf(ApiException.class);
	}

	@Test
	void aGrantNamingAnotherSplitReadsNothingOfThisOne() {
		Seeded item = seed();
		this.grants.grantAdminRead(UUID.randomUUID(), ADMIN, item.jobId(),
				AdminReadItem.forSplitResolution(UUID.randomUUID(), Set.of()), REASON);

		assertThatThrownBy(() -> read(ADMIN, item)).isInstanceOf(ApiException.class);
	}

}
