package vn.vibefix.team.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.MentorDirectory.MentorCard;
import vn.vibefix.identity.MentorGate;
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.identity.StudentDirectory.StudentCard;
import vn.vibefix.identity.StudentVerification;
import vn.vibefix.notification.Audience;
import vn.vibefix.notification.NotificationRequest;
import vn.vibefix.notification.Notifications;
import vn.vibefix.notification.Recipient;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.RateLimitedException;
import vn.vibefix.team.Teams;
import vn.vibefix.team.internal.TeamStore.Kind;
import vn.vibefix.team.internal.TeamStore.Request;
import vn.vibefix.team.internal.TeamStore.RequestStatus;
import vn.vibefix.team.internal.TeamStore.Status;
import vn.vibefix.team.internal.TeamStore.Team;
import vn.vibefix.timers.DeadlineRequest;
import vn.vibefix.timers.Deadlines;

/**
 * Invitations and join requests (FR-6, AD-37): sending them within the daily limits, answering
 * them, and their 14-day lapse. One acceptance command records the membership under the Team row
 * lock, so a full Team or a Student who joined elsewhere meanwhile is refused; the unique index on
 * active memberships settles two Teams accepting the same Student at once.
 */
@Component
class TeamRequests {

	static final String INVITATION_RECEIVED = "team.invitation_received";

	static final String INVITATION_ANSWERED = "team.invitation_answered";

	static final String JOIN_REQUEST_RECEIVED = "team.join_request_received";

	static final String JOIN_REQUEST_ANSWERED = "team.join_request_answered";

	/** An invitation or a join request lapses after this (FR-6). */
	static final Duration LAPSE = Duration.ofDays(14);

	/** Invitations a Mentor may send per day in Vietnam (spine Seed limits). */
	static final int INVITES_PER_DAY = 20;

	/** Join requests a Student may send per day in Vietnam (spine Seed limits). */
	static final int JOIN_REQUESTS_PER_DAY = 5;

	static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	static final String LINK = "/nhom";

	/** How an answer reads in the notification's sentence. */
	private enum Answer {

		ACCEPTED("chấp nhận", "nhận bạn vào nhóm"), DECLINED("từ chối", "từ chối yêu cầu xin vào nhóm của bạn"),
		LAPSED("không trả lời", "không trả lời yêu cầu xin vào nhóm của bạn");

		final String invitation;

		final String joinRequest;

		Answer(String invitation, String joinRequest) {
			this.invitation = invitation;
			this.joinRequest = joinRequest;
		}

	}

	private final TeamStore store;

	private final MentorGate mentorGate;

	private final StudentVerification verification;

	private final StudentDirectory students;

	private final MentorDirectory mentors;

	private final Notifications notifications;

	private final Deadlines deadlines;

	private final AuditLog auditLog;

	private final Clock clock;

	TeamRequests(TeamStore store, MentorGate mentorGate, StudentVerification verification, StudentDirectory students,
			MentorDirectory mentors, Notifications notifications, Deadlines deadlines, AuditLog auditLog,
			Clock clock) {
		this.store = store;
		this.mentorGate = mentorGate;
		this.verification = verification;
		this.students = students;
		this.mentors = mentors;
		this.notifications = notifications;
		this.deadlines = deadlines;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * The Mentor invites a verified Student into their Team.
	 * @throws ApiException {@code FORBIDDEN MENTOR_NOT_APPROVED}; {@code NOT_FOUND} for no such Student;
	 * {@code GUARD_FAILED} with {@code TEAM_CLOSED}, {@code MEMBER_NOT_VERIFIED}, {@code STUDENT_IN_TEAM},
	 * or {@code TEAM_FULL}; {@code CONFLICT REQUEST_PENDING}; {@code RATE_LIMITED TEAM_INVITE_LIMIT}
	 */
	@Transactional
	Request invite(UUID mentorId, UUID studentId) {
		this.mentorGate.requireApprovedMentor(mentorId);
		Instant now = this.clock.instant();
		Team team = this.store.lock(this.store.ensure(mentorId, now).id());
		StudentCard student = this.students.cards(List.of(studentId))
			.stream()
			.filter(StudentCard::available)
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireOpen(team);
		if (!student.verified()) {
			throw guard(TeamReason.MEMBER_NOT_VERIFIED);
		}
		requireJoinable(team, studentId);
		if (this.store.invitationsSince(team.id(), startOfToday()) >= INVITES_PER_DAY) {
			throw new RateLimitedException(untilTomorrow(), TeamReason.TEAM_INVITE_LIMIT);
		}
		Request request = insert(team, studentId, Kind.INVITATION, now);
		this.notifications.send(new NotificationRequest(INVITATION_RECEIVED, request.id(),
				List.of(new Recipient(studentId, Audience.SUBJECT)), Map.of("mentor", mentorName(mentorId)), LINK, null,
				request.id()));
		audit(mentorId, "team.invitation_sent", request);
		return request;
	}

	/**
	 * A verified Student asks to join the Mentor's Team.
	 * @throws ApiException {@code NOT_FOUND} for no such Mentor; {@code GUARD_FAILED} with
	 * {@code MEMBER_NOT_VERIFIED}, {@code TEAM_CLOSED}, {@code STUDENT_IN_TEAM}, or {@code TEAM_FULL};
	 * {@code CONFLICT REQUEST_PENDING}; {@code RATE_LIMITED JOIN_REQUEST_LIMIT}
	 */
	@Transactional
	Request requestToJoin(UUID studentId, UUID mentorId) {
		MentorCard mentor = this.mentors.cards(List.of(mentorId))
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!this.verification.isVerified(studentId)) {
			throw guard(TeamReason.MEMBER_NOT_VERIFIED);
		}
		Instant now = this.clock.instant();
		Optional<Team> existing = this.store.byMentor(mentorId);
		if (!mentor.available()) {
			// A Mentor never approved has no Team to find; one revoked or locked has a closed Team.
			if (existing.isEmpty()) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			throw guard(TeamReason.TEAM_CLOSED);
		}
		Team team = this.store.lock(existing.orElseGet(() -> this.store.ensure(mentorId, now)).id());
		requireOpen(team);
		requireJoinable(team, studentId);
		if (this.store.joinRequestsSince(studentId, startOfToday()) >= JOIN_REQUESTS_PER_DAY) {
			throw new RateLimitedException(untilTomorrow(), TeamReason.JOIN_REQUEST_LIMIT);
		}
		Request request = insert(team, studentId, Kind.JOIN_REQUEST, now);
		this.notifications.send(new NotificationRequest(JOIN_REQUEST_RECEIVED, request.id(),
				List.of(new Recipient(mentorId, Audience.SUBJECT)), Map.of("name", studentName(studentId)), LINK, null,
				request.id()));
		audit(studentId, "team.join_request_sent", request);
		return request;
	}

	/**
	 * The invited Student, or the Mentor of a join request, accepts: the Student joins the Team and
	 * their other pending requests are cancelled.
	 * @param answerer the invited Student for an invitation, the Mentor for a join request
	 * @throws ApiException {@code NOT_FOUND} for a request that is not the answerer's to answer;
	 * {@code CONFLICT REQUEST_CLOSED}; {@code GUARD_FAILED} with {@code TEAM_CLOSED},
	 * {@code MEMBER_NOT_VERIFIED}, {@code STUDENT_IN_TEAM}, or {@code TEAM_FULL}
	 */
	@Transactional
	Request accept(UUID answerer, UUID requestId, Kind kind) {
		Request found = answerable(answerer, requestId, kind);
		if (kind == Kind.JOIN_REQUEST) {
			this.mentorGate.requireApprovedMentor(answerer);
		}
		Instant now = this.clock.instant();
		Team team = this.store.lock(found.teamId());
		Request request = this.store.lockRequest(requestId).orElseThrow();
		if (request.status() != RequestStatus.PENDING) {
			throw new ApiException(ErrorCode.CONFLICT, TeamReason.REQUEST_CLOSED);
		}
		requireOpen(team);
		if (!this.verification.isVerified(request.studentId())) {
			throw guard(TeamReason.MEMBER_NOT_VERIFIED);
		}
		if (this.store.activeMembership(request.studentId()).isPresent()) {
			throw guard(TeamReason.STUDENT_IN_TEAM);
		}
		if (this.store.size(team.id()) >= Teams.SIZE_LIMIT) {
			throw guard(TeamReason.TEAM_FULL);
		}
		try {
			this.store.insertMembership(team.id(), request.studentId(), now);
		}
		catch (DuplicateKeyException ex) {
			throw guard(TeamReason.STUDENT_IN_TEAM);
		}
		this.store.answer(requestId, RequestStatus.ACCEPTED, now);
		this.store.cancelPendingOfStudent(request.studentId(), requestId, now);
		answered(request, Answer.ACCEPTED);
		audit(answerer, "team.member_joined", request);
		return request;
	}

	/**
	 * The invited Student, or the Mentor of a join request, declines.
	 * @throws ApiException {@code NOT_FOUND} for a request that is not the answerer's to answer;
	 * {@code CONFLICT REQUEST_CLOSED}
	 */
	@Transactional
	Request decline(UUID answerer, UUID requestId, Kind kind) {
		answerable(answerer, requestId, kind);
		if (kind == Kind.JOIN_REQUEST) {
			this.mentorGate.requireActiveAccount(answerer);
		}
		Request request = this.store.lockRequest(requestId).orElseThrow();
		if (request.status() != RequestStatus.PENDING) {
			throw new ApiException(ErrorCode.CONFLICT, TeamReason.REQUEST_CLOSED);
		}
		this.store.answer(requestId, RequestStatus.DECLINED, this.clock.instant());
		answered(request, Answer.DECLINED);
		audit(answerer, kind == Kind.INVITATION ? "team.invitation_declined" : "team.join_request_declined", request);
		return request;
	}

	/**
	 * Accepts as {@link #accept} does; when the Student turned out to be in a Team already, the
	 * request can never be accepted, so it is cancelled once the refusal rolled back.
	 */
	Request acceptOrCancel(TeamRequests self, UUID answerer, UUID requestId, Kind kind) {
		try {
			return self.accept(answerer, requestId, kind);
		}
		catch (ApiException ex) {
			if (ex.reason().filter(TeamReason.STUDENT_IN_TEAM.name()::equals).isPresent()) {
				self.cancelStale(requestId);
			}
			throw ex;
		}
	}

	/** Cancels a pending request whose Student joined another Team; no one is notified. */
	@Transactional
	void cancelStale(UUID requestId) {
		this.store.answer(requestId, RequestStatus.CANCELLED, this.clock.instant());
	}

	/** The 14 days passed without an answer: whoever sent it hears so. {@code false} once answered. */
	@Transactional
	boolean lapse(UUID requestId) {
		Optional<Request> request = this.store.lockRequest(requestId)
			.filter(found -> found.status() == RequestStatus.PENDING);
		if (request.isEmpty()) {
			return false;
		}
		this.store.answer(requestId, RequestStatus.LAPSED, this.clock.instant());
		answered(request.get(), Answer.LAPSED);
		this.auditLog.record(AuditEntry.of(AuditActor.system(), "team.request_lapsed",
				AuditObject.of("team.team_request", requestId)));
		return true;
	}

	/** Invitations the Mentor may still send today. */
	int invitesLeft(UUID teamId) {
		return Math.max(0, INVITES_PER_DAY - this.store.invitationsSince(teamId, startOfToday()));
	}

	/** Join requests the Student may still send today. */
	int joinRequestsLeft(UUID studentId) {
		return Math.max(0, JOIN_REQUESTS_PER_DAY - this.store.joinRequestsSince(studentId, startOfToday()));
	}

	/** The request, if it is {@code kind} and the answerer is the side that answers it. */
	private Request answerable(UUID answerer, UUID requestId, Kind kind) {
		return this.store.request(requestId)
			.filter(found -> found.kind() == kind)
			.filter(found -> kind == Kind.INVITATION ? found.studentId().equals(answerer)
					: found.mentorId().equals(answerer))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** Refusals before a request is sent, under the Team row lock. */
	private void requireJoinable(Team team, UUID studentId) {
		if (this.store.activeMembership(studentId).isPresent()) {
			throw guard(TeamReason.STUDENT_IN_TEAM);
		}
		if (this.store.size(team.id()) >= Teams.SIZE_LIMIT) {
			throw guard(TeamReason.TEAM_FULL);
		}
		if (this.store.pendingBetween(team.id(), studentId).isPresent()) {
			throw new ApiException(ErrorCode.CONFLICT, TeamReason.REQUEST_PENDING);
		}
	}

	private static void requireOpen(Team team) {
		if (team.status() != Status.OPEN) {
			throw guard(TeamReason.TEAM_CLOSED);
		}
	}

	private Request insert(Team team, UUID studentId, Kind kind, Instant now) {
		Request request;
		try {
			request = this.store.insertRequest(team.id(), team.mentorId(), studentId, kind, now, now.plus(LAPSE));
		}
		catch (DuplicateKeyException ex) {
			throw new ApiException(ErrorCode.CONFLICT, TeamReason.REQUEST_PENDING);
		}
		this.deadlines.schedule(new DeadlineRequest(TeamRequestLapse.KEY, request.id(), request.id().toString(), null,
				request.lapsesAt()));
		return request;
	}

	/** Tells whoever sent the request how it ended. */
	private void answered(Request request, Answer answer) {
		if (request.kind() == Kind.INVITATION) {
			this.notifications.send(new NotificationRequest(INVITATION_ANSWERED, request.id(),
					List.of(new Recipient(request.mentorId(), Audience.SUBJECT)),
					Map.of("name", studentName(request.studentId()), "answer", answer.invitation), LINK, null,
					request.id()));
		}
		else {
			this.notifications.send(new NotificationRequest(JOIN_REQUEST_ANSWERED, request.id(),
					List.of(new Recipient(request.studentId(), Audience.SUBJECT)),
					Map.of("mentor", mentorName(request.mentorId()), "answer", answer.joinRequest), LINK, null,
					request.id()));
		}
	}

	/** The Mentor's display name, as Students read it. */
	String mentorName(UUID mentorId) {
		return this.mentors.cards(List.of(mentorId)).stream().findFirst().map(MentorCard::displayName).orElse("");
	}

	/** The Student's public name, or their GitHub username when they hide it (FR-51). */
	String studentName(UUID studentId) {
		return this.students.cards(List.of(studentId))
			.stream()
			.findFirst()
			.map(card -> card.name() != null ? card.name() : "@" + card.githubLogin())
			.orElse("");
	}

	private void audit(UUID actor, String action, Request request) {
		this.auditLog.record(AuditEntry.of(AuditActor.user(actor), action, AuditObject.of("team.team_request", request.id()),
				AuditObject.of("team.team", request.teamId())));
	}

	private Instant startOfToday() {
		return LocalDate.ofInstant(this.clock.instant(), VIETNAM).atStartOfDay(VIETNAM).toInstant();
	}

	private Duration untilTomorrow() {
		Instant tomorrow = LocalDate.ofInstant(this.clock.instant(), VIETNAM).plusDays(1).atStartOfDay(VIETNAM).toInstant();
		return Duration.between(this.clock.instant(), tomorrow);
	}

	private static ApiException guard(TeamReason reason) {
		return new ApiException(ErrorCode.GUARD_FAILED, reason);
	}

}
