package vn.vibefix.team.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.AccountLocked;
import vn.vibefix.identity.MentorApprovalRevoked;
import vn.vibefix.identity.MentorApproved;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.Role;
import vn.vibefix.team.internal.TeamStore.Member;
import vn.vibefix.team.internal.TeamStore.Team;

/**
 * A Team's life with its Mentor (AD-37). The Team is created when the Mentor is approved. When the
 * Mentor is revoked or locked, the Team closes to new members, its pending requests are cancelled,
 * and each Student is released in their own short transaction with {@code team.released}; a locked
 * Student leaves their Team. Each step is idempotent, so a replayed event changes nothing more.
 * Crew reservations (Story 1.19) will keep a reserved Student until their reservation ends.
 */
@Component
class TeamLifecycle {

	static final String RELEASED = "team.released";

	private final TeamStore store;

	private final TeamRequests requests;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final TransactionTemplate perStudent;

	private final Clock clock;

	TeamLifecycle(TeamStore store, TeamRequests requests, Notifications notifications, AuditLog auditLog,
			PlatformTransactionManager transactions, Clock clock) {
		this.store = store;
		this.requests = requests;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.perStudent = new TransactionTemplate(transactions);
		this.perStudent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	@ApplicationModuleListener
	void onMentorApproved(MentorApproved event) {
		Team team = this.store.ensure(event.accountId(), this.clock.instant());
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "team.team_created", AuditObject.of("team.team", team.id())));
	}

	@ApplicationModuleListener
	void onMentorApprovalRevoked(MentorApprovalRevoked event) {
		mentorGone(event.accountId());
	}

	@ApplicationModuleListener
	void onAccountLocked(AccountLocked event) {
		if (event.role() == Role.MENTOR) {
			mentorGone(event.accountId());
		}
		else if (event.role() == Role.STUDENT) {
			this.perStudent.executeWithoutResult(status -> studentGone(event.accountId()));
		}
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
			this.perStudent.executeWithoutResult(status -> release(member, mentor));
		}
	}

	private void release(Member member, String mentor) {
		this.store.lockMembership(member.studentId())
			.filter(current -> current.membershipId().equals(member.membershipId()))
			.ifPresent(current -> {
				this.store.endMembership(current.membershipId(), "RELEASED", this.clock.instant());
				this.notifications.send(new NotificationRequest(RELEASED, current.membershipId(),
						List.of(new Recipient(current.studentId(), Audience.SUBJECT)), Map.of("mentor", mentor),
						TeamRequests.LINK, null, current.membershipId()));
				this.auditLog.record(AuditEntry.of(AuditActor.system(), "team.member_released",
						AuditObject.of("team.team", current.teamId())));
			});
	}

	/** A locked Student leaves their Team; their pending requests are cancelled. */
	private void studentGone(UUID studentId) {
		Instant now = this.clock.instant();
		this.store.lockMembership(studentId).ifPresent(current -> {
			this.store.endMembership(current.membershipId(), "RELEASED", now);
			this.auditLog.record(AuditEntry.of(AuditActor.system(), "team.member_released",
					AuditObject.of("team.team", current.teamId())));
		});
		this.store.cancelPendingOfStudent(studentId, null, now);
	}

}
