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

	@Test
	void anOpenDisputeGrantReadsItsScopesOnly() {
		for (AdminReadScope scope : List.of(AdminReadScope.JOB_CHAT, AdminReadScope.WORK_ITEMS,
				AdminReadScope.WORK_ITEM_NOTES, AdminReadScope.ATTACHMENTS, AdminReadScope.PAGES)) {
			assertThat(this.dispute.permits(ADMIN, JOB, scope, ITEM, Set.of(), NOW)).as(scope.name()).isTrue();
		}
		assertThat(this.dispute.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, ITEM, Set.of(), NOW)).isFalse();
		assertThat(this.dispute.permits(ADMIN, JOB, AdminReadScope.CREW_TERMS, ITEM, Set.of(), NOW)).isFalse();
	}

	@Test
	void aGrantIsForItsAdministratorAndItsJob() {
		assertThat(this.dispute.permits(OTHER_ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, Set.of(), NOW)).isFalse();
		assertThat(this.dispute.permits(ADMIN, OTHER_JOB, AdminReadScope.JOB_CHAT, ITEM, Set.of(), NOW)).isFalse();
	}

	@Test
	void aGrantStopsAtItsEndAndAnEndNeverExtendsIt() {
		AdminReadGrant ended = this.dispute.endingAt(NOW.plus(Duration.ofDays(30)));

		assertThat(ended.permits(ADMIN, JOB, AdminReadScope.PAGES, ITEM, Set.of(), NOW.plus(Duration.ofDays(29))))
			.isTrue();
		assertThat(ended.permits(ADMIN, JOB, AdminReadScope.PAGES, ITEM, Set.of(), NOW.plus(Duration.ofDays(30))))
			.isFalse();
		assertThat(ended.endingAt(NOW.plus(Duration.ofDays(60))).endsAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		assertThat(ended.endingAt(NOW).endsAt()).isEqualTo(NOW);
	}

	@Test
	void anEscalatedTeamIssueGrantReadsTheRaisersWorkItemsOnly() {
		AdminReadGrant teamIssue = AdminReadGrant.open(GRANT, ADMIN, JOB, AdminReadItem.forEscalatedTeamIssue(RAISER),
				DISPUTE);

		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.CREW_CHAT, ITEM, Set.of(), NOW)).isTrue();
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.CREW_TERMS, ITEM, Set.of(), NOW)).isTrue();
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.WORK_ITEMS, ITEM, Set.of(RAISER, OTHER_ADMIN), NOW))
			.isTrue();
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.WORK_ITEMS, ITEM, Set.of(OTHER_ADMIN), NOW)).isFalse();
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.WORK_ITEM_NOTES, ITEM, Set.of(RAISER), NOW)).isFalse();
		assertThat(teamIssue.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, Set.of(RAISER), NOW)).isFalse();
	}

	@Test
	void anAbuseReportGrantReadsTheOneItemItNames() {
		AdminReadGrant report = AdminReadGrant.open(GRANT, ADMIN, JOB,
				AdminReadItem.forAbuseReport(AdminReadScope.JOB_CHAT, ITEM), DISPUTE);

		assertThat(report.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, ITEM, Set.of(), NOW)).isTrue();
		assertThat(report.permits(ADMIN, JOB, AdminReadScope.JOB_CHAT, RAISER, Set.of(), NOW)).isFalse();
		assertThat(report.permits(ADMIN, JOB, AdminReadScope.PAGES, ITEM, Set.of(), NOW)).isFalse();
	}

	@Test
	void anItemCoversOneObjectOrOneAccountNotBoth() {
		assertThatThrownBy(() -> new AdminReadItem(AdminReadScope.PAGES, ITEM, RAISER))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AdminReadGrant.open(GRANT, ADMIN, JOB, Set.of(), DISPUTE))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
