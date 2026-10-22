package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.shared.AdminReadGrants;
import vn.vibefix.shared.AdminReadItem;
import vn.vibefix.shared.AdminReadScope;
import vn.vibefix.support.AdminReadGrantsContract;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;

/**
 * AD-20, AD-8, AD-40: {@code job}'s Administrator read grants for the Crew Terms versions meet the Story
 * 1.3 contract, and a grant serves every version with each party's share.
 */
class CrewTermsGrantsIT extends IntegrationTest implements AdminReadGrantsContract {

	@Autowired
	private CrewTermsGrants grants;

	@Autowired
	private JdbcClient jdbc;

	@Override
	public AdminReadGrants grants() {
		return this.grants;
	}

	@Override
	public AdminReadScope scope() {
		return AdminReadScope.CREW_TERMS;
	}

	@Override
	public Seeded seed() {
		UUID job = HeldCrewJob.create(this.jdbc, this.clock.instant()).job();
		return new Seeded(job, AdminReadScope.CREW_TERMS, job);
	}

	@Override
	public void read(UUID adminId, Seeded item) {
		this.grants.crewTerms(adminId, item.jobId());
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
	void anEscalatedTeamIssuesGrantServesEveryVersionWithEachShare() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		this.grants.grantAdminRead(UUID.randomUUID(), ADMIN, world.job(), AdminReadItem.forEscalatedTeamIssue(world.vy()),
				REASON);

		List<CrewTermsGrants.Version> versions = this.grants.crewTerms(ADMIN, world.job());

		assertThat(versions).singleElement().satisfies(version -> {
			assertThat(version.status()).isEqualTo("IN_FORCE");
			assertThat(version.shares()).extracting(CrewTermsGrants.Share::percent).containsExactly(40, 40, 20);
			assertThat(Set.copyOf(version.shares().stream().map(CrewTermsGrants.Share::accountId).toList()))
				.containsExactlyInAnyOrder(world.hung(), world.vy(), world.minh());
		});
	}

}
