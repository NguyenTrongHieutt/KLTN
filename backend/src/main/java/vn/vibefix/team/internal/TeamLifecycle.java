package vn.vibefix.team.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.team.internal.TeamStore.Member;
import vn.vibefix.team.internal.TeamStore.Team;

/**
 * A Team's life with its Mentor (AD-37). Nobody is approved since Story 19.7, so no Team is created at an
 * approval: {@link TeamRequests} creates one on demand until Story 21.7 retires Teams. Every lock runs both
 * consequences, since an account holds no role (Story 20.3), and each acts only on what the account holds: when
 * it is a Team's Mentor, the Team closes to new members, its pending requests are cancelled, and each Student is
 * released in their own short transaction with {@code team.released}, unless a Crew reservation holds them:
 * {@link TeamMemberships#releaseCrew} releases them when it ends. When it is a Team member, it leaves its Team the
 * same way. Each step is idempotent, so a replayed event changes nothing more.
 */
@Component
class TeamLifecycle {

	private final TeamStore store;

	private final TeamRequests requests;

	private final TeamMemberships memberships;

	private final AuditLog auditLog;

	private final TransactionTemplate perStudent;

	private final Clock clock;

	TeamLifecycle(TeamStore store, TeamRequests requests, TeamMemberships memberships, AuditLog auditLog,
			PlatformTransactionManager transactions, Clock clock) {
		this.store = store;
		this.requests = requests;
		this.memberships = memberships;
		this.auditLog = auditLog;
		this.perStudent = new TransactionTemplate(transactions);
		this.perStudent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		mentorGone(event.accountId());
		this.perStudent.executeWithoutResult(status -> studentGone(event.accountId()));
	}

	/** Closes the Mentor's Team, then releases each Student on their own. */
	private void mentorGone(UUID mentorId) {
		List<Member> members = this.perStudent.execute(status -> this.store.byMentor(mentorId).map(found -> {
			Team team = this.store.lock(found.id());
			Instant now = this.clock.instant();
			this.store.close(team.id(), now);
			this.store.cancelPendingOfTeam(team.id(), now);
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "team.team_closed", AuditObject.of("team.team", team.id())));
			return this.store.members(team.id());
		}).orElse(List.of()));
		String mentor = this.requests.mentorName(mentorId);
		for (Member member : members == null ? List.<Member>of() : members) {
			this.perStudent.executeWithoutResult(status -> this.store.lockMembership(member.studentId())
				.filter(current -> current.membershipId().equals(member.membershipId()))
				.ifPresent(current -> this.memberships.releaseUnlessOnCrew(current, mentor, true)));
		}
	}

	/** A locked Student leaves their Team unless on a Crew; their pending requests are cancelled. */
	private void studentGone(UUID studentId) {
		this.store.lockMembership(studentId)
			.ifPresent(current -> this.memberships.releaseUnlessOnCrew(current, this.requests.mentorName(current.mentorId()),
					false));
		this.store.cancelPendingOfStudent(studentId, null, this.clock.instant());
	}

}
