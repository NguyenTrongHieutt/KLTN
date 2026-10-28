package vn.vibefix.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.support.HeldCrewJob;
import vn.vibefix.support.IntegrationTest;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * AD-20, UX-DR112 (Story 20.5): {@code job} confirms {@code SPLIT_PARTY} as the parties of the Crew Terms version
 * pinned to the Milestone, never the Customer, and {@code notification} refuses a split notice to anyone else or
 * without its Milestone.
 */
class SplitPartyRecipientsIT extends IntegrationTest {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JobRecipients recipients;

	@Autowired
	private Notifications notifications;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void theSplitPartiesAreThePartiesOfTheCrewTermsPinnedToTheMilestone() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID unrelated = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);

		for (UUID party : List.of(world.hung(), world.vy(), world.minh())) {
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), world.milestone())).isTrue();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), null)).as("without the Milestone")
				.isFalse();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job())).isFalse();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), UUID.randomUUID())).isFalse();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, UUID.randomUUID(), world.milestone())).isFalse();
		}
		assertThat(this.recipients.holds(world.lan(), Audience.SPLIT_PARTY, world.job(), world.milestone()))
			.as("the Customer is never a split party")
			.isFalse();
		assertThat(this.recipients.holds(unrelated, Audience.SPLIT_PARTY, world.job(), world.milestone())).isFalse();
		assertThat(this.recipients.holds(world.vy(), Audience.CREW_ACTIVE, world.job(), world.milestone())).isTrue();
	}

	/**
	 * A second Crew Terms version adds An and drops Minh, and a second Milestone is pinned to it: each Milestone's
	 * split parties are those of its own pinned version, whatever version is in force now.
	 */
	@Test
	void eachMilestonesSplitPartiesAreThoseOfTheVersionPinnedToIt() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID an = TeamTestSupport.student(this.jdbc, "Phan Văn An", true, List.of(TeamTestSupport.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID row = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.crew_row (id, job_id, member_account_id, status, signed_at, activated_at, created_at)
				VALUES (:id, :job, :an, 'ACTIVE', now(), now(), now())
				""").param("id", row).param("job", world.job()).param("an", an).update();
		this.jdbc.sql("UPDATE job.crew_terms_version SET status = 'SUPERSEDED' WHERE job_id = :job AND version_no = 1")
			.param("job", world.job())
			.update();
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_version (job_id, version_no, status, cap_percent, author_account_id, created_at)
				VALUES (:job, 2, 'IN_FORCE', 40, :hung, now())
				""").param("job", world.job()).param("hung", world.hung()).update();
		share(world.job(), world.hung(), null, 20);
		share(world.job(), world.vy(), crewRow(world.job(), world.vy()), 40);
		share(world.job(), an, row, 40);
		UUID second = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO job.milestone (id, job_id, position, title, price_vnd, revision_rounds, created_at, state,
				    funded_at, crew_terms_version_no)
				VALUES (:id, :job, 1, 'Giai đoạn 2', 500000, 2, now(), 'FUNDED', now(), 2)
				""").param("id", second).param("job", world.job()).update();

		for (UUID party : List.of(world.hung(), world.vy())) {
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), world.milestone())).isTrue();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), second)).isTrue();
		}
		assertThat(this.recipients.holds(world.minh(), Audience.SPLIT_PARTY, world.job(), world.milestone()))
			.as("Minh is a party of version 1").isTrue();
		assertThat(this.recipients.holds(world.minh(), Audience.SPLIT_PARTY, world.job(), second))
			.as("version 2 dropped Minh").isFalse();
		assertThat(this.recipients.holds(an, Audience.SPLIT_PARTY, world.job(), world.milestone()))
			.as("version 1 never named An").isFalse();
		assertThat(this.recipients.holds(an, Audience.SPLIT_PARTY, world.job(), second)).as("version 2 added An").isTrue();
	}

	/**
	 * A member released {@code LEFT} after the version pinned to the Milestone named them stays its split party
	 * (UX-DR112, leavers included), though no longer on the Crew.
	 */
	@Test
	void aMemberWhoLeftStaysASplitPartyOfTheVersionThatNamesThem() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		Timestamp left = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				UPDATE job.crew_row SET status = 'RELEASED', released_reason = 'LEFT', released_at = :at, window_end = :at
				WHERE job_id = :job AND member_account_id = :minh
				""").param("at", left).param("job", world.job()).param("minh", world.minh()).update();

		assertThat(this.recipients.holds(world.minh(), Audience.SPLIT_PARTY, world.job(), world.milestone()))
			.as("leavers included")
			.isTrue();
		assertThat(this.recipients.holds(world.minh(), Audience.CREW_ACTIVE, world.job(), world.milestone()))
			.as("no longer on the Crew")
			.isFalse();
	}

	private void share(UUID job, UUID party, UUID row, int percent) {
		this.jdbc.sql("""
				INSERT INTO job.crew_terms_share (job_id, version_no, party_account_id, crew_row_id, percent)
				VALUES (:job, 2, :party, :row, :percent)
				""").param("job", job).param("party", party).param("row", row).param("percent", percent).update();
	}

	private UUID crewRow(UUID job, UUID member) {
		return this.jdbc.sql("SELECT id FROM job.crew_row WHERE job_id = :job AND member_account_id = :member")
			.param("job", job)
			.param("member", member)
			.query(UUID.class)
			.single();
	}

	@Test
	void aSplitNoticeReachesNoOneOutsideTheSplit() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());

		send(world, world.vy(), Audience.SPLIT_PARTY, world.milestone());
		assertThat(notices(world.vy())).isOne();
		assertThatThrownBy(() -> send(world, world.lan(), Audience.SPLIT_PARTY, world.milestone()))
			.hasMessageContaining("not confirmed as SPLIT_PARTY");
		assertThatThrownBy(() -> send(world, world.lan(), Audience.CUSTOMER, world.milestone()))
			.hasMessageContaining("does not reach the audience class CUSTOMER");
		assertThatThrownBy(() -> send(world, world.minh(), Audience.SPLIT_PARTY, null))
			.hasMessageContaining("SPLIT_PARTY needs the Milestone");
		assertThatThrownBy(() -> send(world, world.minh(), Audience.CREW_EVER_ACTIVE, world.milestone()))
			.hasMessageContaining("does not reach the audience class CREW_EVER_ACTIVE");
		assertThat(notices(world.lan()) + notices(world.minh())).isZero();
	}

	private void send(HeldCrewJob world, UUID recipient, Audience audience, UUID milestone) {
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> this.notifications
			.send(new NotificationRequest("payment.share_held", UUID.randomUUID(), List.of(new Recipient(recipient, audience)),
					Map.of("n", "1"), "/so-du", world.job(), null, milestone)));
	}

	private long notices(UUID account) {
		return this.jdbc.sql("SELECT count(*) FROM notification.notification WHERE type = 'payment.share_held' AND account_id = :id")
			.param("id", account)
			.query(Long.class)
			.single();
	}

}
