package vn.vibefix.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** AD-20: what an Administrator read grant permits, and the named scope of each case kind. */
class AdminReadGrantTest {

	private static final UUID GRANT = UUID.fromString("0190f0c3-0000-7000-8000-00000000b001");

	private static final UUID ADMIN = UUID.fromString("0190f0c3-0000-7000-8000-00000000b002");

	private static final UUID OTHER_ADMIN = UUID.fromString("0190f0c3-0000-7000-8000-00000000b003");

	private static final UUID JOB = UUID.fromString("0190f0c3-0000-7000-8000-00000000b004");

	private static final UUID OTHER_JOB = UUID.fromString("0190f0c3-0000-7000-8000-00000000b005");

	private static final UUID DISPUTE = UUID.fromString("0190f0c3-0000-7000-8000-00000000b006");

	private static final UUID RAISER = UUID.fromString("0190f0c3-0000-7000-8000-00000000b007");

	private static final UUID ITEM = UUID.fromString("0190f0c3-0000-7000-8000-00000000b008");

	private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

	private final AdminReadGrant dispute = AdminReadGrant.open(GRANT, ADMIN, JOB, AdminReadItem.forDispute(), DISPUTE);

	/** Story 24.2: the Board is retired, so a Dispute's grant is the Job Chat and nothing else. */
	@Test
	void anOpenDisputeGrantReadsTheJobChatOnly() {
		assertThat(this.dispute.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW)).isTrue();
		assertThat(this.dispute.items()).extracting(AdminReadItem::scope).containsExactly(AdminReadScope.JOB_CHAT);
		assertThat(this.dispute.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, ITEM, NOW)).isFalse();
		assertThat(this.dispute.permits(ADMIN, JOB, AdminReadScope.SPLIT_VERSIONS, ITEM, NOW)).isFalse();
	}

	@Test
	void aGrantIsForItsAdministratorAndItsJob() {
		assertThat(this.dispute.permits(OTHER_ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW)).isFalse();
		assertThat(this.dispute.permits(ADMIN, OTHER_JOB, AdminReadScope.JOB_CHAT, ITEM, NOW)).isFalse();
	}

	@Test
	void aGrantStopsAtItsEndAndAnEndNeverExtendsIt() {
		AdminReadGrant ended = this.dispute.endingAt(NOW.plus(Duration.ofDays(30)));

		assertThat(ended.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW.plus(Duration.ofDays(29))))
			.isTrue();
		assertThat(ended.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW.plus(Duration.ofDays(30))))
			.isFalse();
		assertThat(ended.endingAt(NOW.plus(Duration.ofDays(60))).endsAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		assertThat(ended.endingAt(NOW).endsAt()).isEqualTo(NOW);
	}

	/** Story 24.2: with the Board gone the escalated issue's grant is the Crew Chat; the Rating is read through reputation. */
	@Test
	void anEscalatedTeamIssueGrantReadsTheCrewChatOnly() {
		AdminReadGrant teamIssue = AdminReadGrant.open(GRANT, ADMIN, JOB, AdminReadItem.forEscalatedTeamIssue(), DISPUTE);

		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, ITEM, NOW)).isTrue();
		assertThat(teamIssue.items()).as("Story 22.4: no Crew Terms scope; Story 24.2: no Work Item scope")
			.extracting(AdminReadItem::scope)
			.containsExactly(AdminReadScope.CREW_CHAT);
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.SPLIT_VERSIONS, ITEM, NOW)).isFalse();
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW)).isFalse();
	}

	/** Story 22.3 (AD-20, A-20), narrowed by Story 24.2: one split's versions and the Crew Chat, never the Job Chat. */
	@Test
	void aSplitResolutionGrantReadsItsSplitAndTheCrewChatOnly() {
		AdminReadGrant resolution = AdminReadGrant.open(GRANT, ADMIN, JOB, AdminReadItem.forSplitResolution(ITEM), DISPUTE);

		assertThat(resolution.permits(ADMIN, JOB, AdminReadScope.SPLIT_VERSIONS, ITEM, NOW)).isTrue();
		assertThat(resolution.permits(ADMIN, JOB, AdminReadScope.SPLIT_VERSIONS, RAISER, NOW)).as("another split").isFalse();
		assertThat(resolution.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, DISPUTE, NOW)).isTrue();
		assertThat(resolution.items()).extracting(AdminReadItem::scope)
			.containsExactlyInAnyOrder(AdminReadScope.SPLIT_VERSIONS, AdminReadScope.CREW_CHAT);
		assertThat(resolution.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW)).isFalse();
		assertThat(this.dispute.permits(ADMIN, JOB, AdminReadScope.SPLIT_VERSIONS, ITEM, NOW))
			.as("a Dispute's grant covers no split version")
			.isFalse();
	}

	@Test
	void anAbuseReportGrantReadsTheOneItemItNames() {
		AdminReadGrant report = AdminReadGrant.open(GRANT, ADMIN, JOB,
				AdminReadItem.forAbuseReport(AdminReadScope.CREW_CHAT, ITEM), DISPUTE);

		assertThat(report.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, ITEM, NOW)).isTrue();
		assertThat(report.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, RAISER, NOW)).isFalse();
		assertThat(report.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, NOW)).isFalse();
	}

	/** Story 24.2: the enum holds the two Chats and a Milestone Split's versions, and nothing of the Board. */
	@Test
	void theScopesAreTheTwoChatsAndASplitsVersions() {
		assertThat(List.of(AdminReadScope.values())).containsExactly(AdminReadScope.JOB_CHAT, AdminReadScope.CREW_CHAT,
				AdminReadScope.SPLIT_VERSIONS);
	}

	/**
	 * Story 24.2: an item is all of a scope or one object of it, and nothing else. The account-scoped breadth went
	 * with the Work Item histories it was made for, so a grant has no way to name a person's content.
	 */
	@Test
	void anItemIsAllOfAScopeOrOneObjectOfIt() {
		assertThat(AdminReadItem.all(AdminReadScope.CREW_CHAT).objectId()).isNull();
		assertThat(AdminReadItem.all(AdminReadScope.CREW_CHAT).covers(AdminReadScope.CREW_CHAT, ITEM)).isTrue();
		assertThat(AdminReadItem.one(AdminReadScope.SPLIT_VERSIONS, ITEM).covers(AdminReadScope.SPLIT_VERSIONS, RAISER))
			.isFalse();
		assertThatThrownBy(() -> AdminReadItem.one(AdminReadScope.CREW_CHAT, null))
			.isInstanceOf(NullPointerException.class);
	}

	/** A grant that covers nothing is no grant: every reader builds the record before it filters (P2's defect). */
	@Test
	void aGrantCoversAtLeastOneItem() {
		assertThatThrownBy(() -> AdminReadGrant.open(GRANT, ADMIN, JOB, Set.of(), DISPUTE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least one item");
	}

}
