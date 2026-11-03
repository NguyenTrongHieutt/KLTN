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
import vn.vibefix.support.TestPeople;

/**
 * AD-20, UX-DR112, A-11 (Stories 20.5 and 22.1): {@code job} confirms {@code SPLIT_PARTY} as the parties other than the
 * Mentor of the Milestone's split, never the Customer; {@code MENTOR} with a Milestone also holds for the split's
 * Mentor, so a payee is reached after the Job's row has gone; and {@code notification} refuses a split notice to anyone
 * else or without its Milestone.
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
	void theSplitPartiesAreTheCrewPartiesOfTheMilestonesSplit() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy(), world.minh());
		UUID unrelated = IdentityTestSupport.insertAccount(this.jdbc, Role.STUDENT, null);

		for (UUID party : List.of(world.vy(), world.minh())) {
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), world.milestone())).isTrue();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), null)).as("without the Milestone")
				.isFalse();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job())).isFalse();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, world.job(), UUID.randomUUID())).isFalse();
			assertThat(this.recipients.holds(party, Audience.SPLIT_PARTY, UUID.randomUUID(), world.milestone())).isFalse();
		}
		assertThat(this.recipients.holds(world.hung(), Audience.SPLIT_PARTY, world.job(), world.milestone()))
			.as("the Mentor is reached as the Mentor")
			.isFalse();
		assertThat(this.recipients.holds(world.lan(), Audience.SPLIT_PARTY, world.job(), world.milestone()))
			.as("the Customer is never a split party")
			.isFalse();
		assertThat(this.recipients.holds(unrelated, Audience.SPLIT_PARTY, world.job(), world.milestone())).isFalse();
		assertThat(this.recipients.holds(world.vy(), Audience.CREW_ACTIVE, world.job(), world.milestone())).isTrue();
	}

	/** A later member is a party of the second Milestone only, and a member who was not a party of it is not reached. */
	@Test
	void eachMilestonesSplitPartiesAreItsOwn() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		UUID an = TestPeople.student(this.jdbc, "Phan Văn An", true, List.of(TestPeople.REACT), "React",
				LocalDate.parse("2026-09-30"));
		UUID second = UUID.randomUUID();
		world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy(), world.minh());
		world.split(this.jdbc, this.clock.instant(), second, world.vy(), an);

		assertThat(this.recipients.holds(world.vy(), Audience.SPLIT_PARTY, world.job(), world.milestone())).isTrue();
		assertThat(this.recipients.holds(world.vy(), Audience.SPLIT_PARTY, world.job(), second)).isTrue();
		assertThat(this.recipients.holds(world.minh(), Audience.SPLIT_PARTY, world.job(), world.milestone())).isTrue();
		assertThat(this.recipients.holds(world.minh(), Audience.SPLIT_PARTY, world.job(), second)).isFalse();
		assertThat(this.recipients.holds(an, Audience.SPLIT_PARTY, world.job(), world.milestone())).isFalse();
		assertThat(this.recipients.holds(an, Audience.SPLIT_PARTY, world.job(), second)).isTrue();
	}

	/** A member released {@code LEFT} stays a party of the split that names them (UX-DR112, leavers included). */
	@Test
	void aMemberWhoLeftStaysASplitPartyOfTheSplitThatNamesThem() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy(), world.minh());
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

	@Test
	void aPayeeIsReachedAfterTheJobsRowHasGoneAndNobodyElseIs() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy());
		assertThat(this.recipients.holds(world.hung(), Audience.MENTOR, world.job())).isTrue();
		assertThat(this.recipients.holds(world.lan(), Audience.CUSTOMER, world.job())).isTrue();

		this.jdbc.sql("UPDATE job.job SET product_deleted_at = now() WHERE id = :id").param("id", world.job()).update();

		assertThat(this.recipients.holds(world.hung(), Audience.MENTOR, world.job(), world.milestone())).isTrue();
		assertThat(this.recipients.holds(world.vy(), Audience.SPLIT_PARTY, world.job(), world.milestone())).isTrue();
		assertThat(this.recipients.holds(world.hung(), Audience.MENTOR, world.job())).as("no Milestone named").isFalse();
		assertThat(this.recipients.holds(world.lan(), Audience.CUSTOMER, world.job())).as("the Job is gone for her").isFalse();
		assertThat(this.recipients.holds(world.vy(), Audience.MENTOR, world.job(), world.milestone()))
			.as("a member is never the split's Mentor")
			.isFalse();
	}

	@Test
	void aSplitNoticeReachesNoOneOutsideTheSplit() {
		HeldCrewJob world = HeldCrewJob.create(this.jdbc, this.clock.instant());
		world.split(this.jdbc, this.clock.instant(), world.milestone(), world.vy(), world.minh());

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
			.send(new NotificationRequest("job.split_pending", UUID.randomUUID(), List.of(new Recipient(recipient, audience)),
					Map.of("n", "1", "job", "Sửa lỗi đặt bàn", "date", "10/11"), "/cong-viec/" + world.job() + "/phan-chia",
					world.job(), null, milestone)));
	}

	private long notices(UUID account) {
		return this.jdbc
			.sql("SELECT count(*) FROM notification.notification WHERE type = 'job.split_pending' AND account_id = :id")
			.param("id", account)
			.query(Long.class)
			.single();
	}

}
