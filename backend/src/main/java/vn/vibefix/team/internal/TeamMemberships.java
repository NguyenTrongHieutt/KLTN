package vn.vibefix.team.internal;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.team.Teams;
import vn.vibefix.team.internal.TeamStore.Member;
import vn.vibefix.team.internal.TeamStore.Status;
import vn.vibefix.team.internal.TeamStore.Team;

/**
 * How memberships end (FR-6, AD-37), and the Crew reservations that hold them. Leaving, removal,
 * the releases, and {@link #reserveForCrew} all lock the Student's active membership row first and
 * only then read its reservations, so a reservation and an end racing each other never both win.
 */
@Component
class TeamMemberships implements Teams {

	static final String MEMBER_LEFT = "team.member_left";

	static final String MEMBER_REMOVED = "team.member_removed";

	static final String RELEASED = "team.released";

	private final TeamStore store;

	private final TeamRequests requests;

	private final MentorGate mentorGate;

	private final StudentDirectory students;

	private final Notifications notifications;

	private final AuditLog auditLog;

	private final Clock clock;

	TeamMemberships(TeamStore store, TeamRequests requests, MentorGate mentorGate, StudentDirectory students,
			Notifications notifications, AuditLog auditLog, Clock clock) {
		this.store = store;
		this.requests = requests;
		this.mentorGate = mentorGate;
		this.students = students;
		this.notifications = notifications;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	@Override
	public boolean isMember(UUID mentorId, UUID studentId) {
		return this.store.isMember(mentorId, studentId);
	}

	@Override
	public int sizeOf(UUID mentorId) {
		return this.store.sizeOf(mentorId);
	}

	@Override
	public List<UUID> members(UUID mentorId) {
		return this.store.studentsOf(mentorId);
	}

	@Override
	public Map<UUID, List<UUID>> membersOf(Collection<UUID> mentorIds) {
		return this.store.studentsOf(mentorIds);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserveForCrew(UUID crewRowId, UUID mentorId, UUID studentId, UUID jobId) {
		Member member = this.store.lockMembership(studentId)
			.filter(found -> found.mentorId().equals(mentorId))
			.orElseThrow(() -> new ApiException(ErrorCode.GUARD_FAILED, TeamReason.NOT_IN_TEAM));
		this.store.reserve(crewRowId, member.membershipId(), jobId, this.clock.instant());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseCrew(UUID crewRowId) {
		Optional<UUID> membershipId = this.store.reservedMembership(crewRowId);
		if (membershipId.isEmpty()) {
			return;
		}
		Optional<Member> member = this.store.membershipById(membershipId.get())
			.flatMap(found -> this.store.lockMembership(found.studentId()))
			.filter(found -> found.membershipId().equals(membershipId.get()));
		this.store.unreserve(crewRowId);
		member.ifPresent(found -> {
			Team team = this.store.byId(found.teamId());
			boolean studentGone = this.students.cards(List.of(found.studentId()))
				.stream()
				.noneMatch(StudentCard::available);
			if (team.status() == Status.CLOSED || studentGone) {
				release(found, this.requests.mentorName(found.mentorId()), !studentGone);
			}
		});
	}

	/**
	 * The Student leaves their Team; the Mentor hears so.
	 * @throws ApiException {@code NOT_FOUND} in no Team; {@code GUARD_FAILED STUDENT_ON_CREW}
	 */
	@Transactional
	void leave(UUID studentId) {
		Member member = this.store.lockMembership(studentId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireNoCrew(member);
		this.store.endMembership(member.membershipId(), "LEFT", this.clock.instant());
		this.notifications.send(new NotificationRequest(MEMBER_LEFT, member.membershipId(),
				List.of(new Recipient(member.mentorId(), Audience.SUBJECT)),
				Map.of("name", this.requests.studentName(studentId)), TeamRequests.LINK, null, member.membershipId()));
		this.auditLog.record(AuditEntry.of(AuditActor.user(studentId), "team.member_left",
				AuditObject.of("team.team", member.teamId())));
	}

	/**
	 * The Mentor removes a Student from their Team; the Student hears so. Needs only an unlocked
	 * account, so a revoked Mentor can still let Students go (FR-74).
	 * @throws ApiException {@code NOT_FOUND} for another Team's membership; {@code GUARD_FAILED STUDENT_ON_CREW}
	 */
	@Transactional
	void remove(UUID mentorId, UUID membershipId) {
		this.mentorGate.requireActiveAccount(mentorId);
		Team team = this.store.byMentor(mentorId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Member found = this.store.activeMember(team.id(), membershipId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Member member = this.store.lockMembership(found.studentId())
			.filter(locked -> locked.membershipId().equals(membershipId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireNoCrew(member);
		this.store.endMembership(membershipId, "REMOVED", this.clock.instant());
		this.notifications.send(new NotificationRequest(MEMBER_REMOVED, membershipId,
				List.of(new Recipient(member.studentId(), Audience.SUBJECT)),
				Map.of("mentor", this.requests.mentorName(mentorId)), TeamRequests.LINK, null, membershipId));
		this.auditLog.record(AuditEntry.of(AuditActor.user(mentorId), "team.member_removed",
				AuditObject.of("team.team", member.teamId())));
	}

	/**
	 * Releases a member whose Mentor is gone or who is locked, unless a Crew reservation still holds
	 * them; {@code releaseCrew} releases them later. The caller holds the membership's lock.
	 * @return whether the membership ended
	 */
	boolean releaseUnlessOnCrew(Member member, String mentorName, boolean notify) {
		if (this.store.reserved(member.membershipId())) {
			return false;
		}
		release(member, mentorName, notify);
		return true;
	}

	private void release(Member member, String mentorName, boolean notify) {
		this.store.endMembership(member.membershipId(), "RELEASED", this.clock.instant());
		if (notify) {
			this.notifications.send(new NotificationRequest(RELEASED, member.membershipId(),
					List.of(new Recipient(member.studentId(), Audience.SUBJECT)), Map.of("mentor", mentorName),
					TeamRequests.LINK, null, member.membershipId()));
		}
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "team.member_released",
				AuditObject.of("team.team", member.teamId())));
	}

	private void requireNoCrew(Member member) {
		if (this.store.reserved(member.membershipId())) {
			throw new ApiException(ErrorCode.GUARD_FAILED, TeamReason.STUDENT_ON_CREW);
		}
	}

}
