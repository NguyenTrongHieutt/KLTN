package vn.vibefix.team.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.shared.IdGenerator;

/**
 * The {@code team} tables. Lock order (AD-37): the Team row, then membership rows in ascending
 * Student ID, then request rows; a membership row is locked before its reservations are read.
 */
@Component
class TeamStore {

	enum Status {
		OPEN, CLOSED
	}

	enum Kind {
		INVITATION, JOIN_REQUEST
	}

	enum RequestStatus {
		PENDING, ACCEPTED, DECLINED, LAPSED, CANCELLED
	}

	record Team(UUID id, UUID mentorId, Status status, Instant createdAt) {
	}

	record Member(UUID membershipId, UUID teamId, UUID mentorId, UUID studentId, Instant joinedAt) {
	}

	record Request(UUID id, UUID teamId, UUID mentorId, UUID studentId, Kind kind, RequestStatus status,
			Instant createdAt, Instant lapsesAt) {
	}

	private static final String REQUEST_COLUMNS = """
			r.id, r.team_id, t.mentor_account_id, r.student_account_id, r.kind, r.status, r.created_at, r.lapses_at
			""";

	private final JdbcClient jdbc;

	private final IdGenerator ids;

	TeamStore(JdbcClient jdbc, IdGenerator ids) {
		this.jdbc = jdbc;
		this.ids = ids;
	}

	boolean isMember(UUID mentorId, UUID studentId) {
		return activeMembership(studentId).filter(member -> member.mentorId().equals(mentorId)).isPresent();
	}

	int sizeOf(UUID mentorId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM team.membership m JOIN team.team t ON t.id = m.team_id
				WHERE t.mentor_account_id = :mentor AND m.left_at IS NULL
				""").param("mentor", mentorId).query(Integer.class).single();
	}

	Optional<Team> byMentor(UUID mentorId) {
		return this.jdbc.sql("SELECT id, mentor_account_id, status, created_at FROM team.team WHERE mentor_account_id = :mentor")
			.param("mentor", mentorId)
			.query(TeamStore::team)
			.optional();
	}

	/** The Mentor's Team, created {@code OPEN} if it does not exist yet; idempotent. */
	Team ensure(UUID mentorId, Instant now) {
		this.jdbc.sql("""
				INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', :now)
				ON CONFLICT (mentor_account_id) DO NOTHING
				""").param("id", this.ids.newId()).param("mentor", mentorId).param("now", Timestamp.from(now)).update();
		return byMentor(mentorId).orElseThrow();
	}

	Team byId(UUID teamId) {
		return this.jdbc.sql("SELECT id, mentor_account_id, status, created_at FROM team.team WHERE id = :id")
			.param("id", teamId)
			.query(TeamStore::team)
			.single();
	}

	/** Locks the Team row, first in the lock order. */
	Team lock(UUID teamId) {
		return this.jdbc.sql("SELECT id, mentor_account_id, status, created_at FROM team.team WHERE id = :id FOR UPDATE")
			.param("id", teamId)
			.query(TeamStore::team)
			.single();
	}

	void close(UUID teamId, Instant now) {
		this.jdbc.sql("UPDATE team.team SET status = 'CLOSED', closed_at = :now WHERE id = :id AND status = 'OPEN'")
			.param("id", teamId)
			.param("now", Timestamp.from(now))
			.update();
	}

	int size(UUID teamId) {
		return this.jdbc.sql("SELECT count(*) FROM team.membership WHERE team_id = :team AND left_at IS NULL")
			.param("team", teamId)
			.query(Integer.class)
			.single();
	}

	/** The Team's members, longest-standing first. */
	List<Member> members(UUID teamId) {
		return this.jdbc.sql("""
				SELECT m.id, m.team_id, t.mentor_account_id, m.student_account_id, m.joined_at
				FROM team.membership m JOIN team.team t ON t.id = m.team_id
				WHERE m.team_id = :team AND m.left_at IS NULL ORDER BY m.joined_at, m.student_account_id
				""").param("team", teamId).query(TeamStore::member).list();
	}

	Optional<Member> activeMembership(UUID studentId) {
		return this.jdbc.sql("""
				SELECT m.id, m.team_id, t.mentor_account_id, m.student_account_id, m.joined_at
				FROM team.membership m JOIN team.team t ON t.id = m.team_id
				WHERE m.student_account_id = :student AND m.left_at IS NULL
				""").param("student", studentId).query(TeamStore::member).optional();
	}

	/** Locks the Student's active membership row, as leaving, removal, and the release do (AD-37). */
	Optional<Member> lockMembership(UUID studentId) {
		return this.jdbc.sql("""
				SELECT m.id, m.team_id, t.mentor_account_id, m.student_account_id, m.joined_at
				FROM team.membership m JOIN team.team t ON t.id = m.team_id
				WHERE m.student_account_id = :student AND m.left_at IS NULL FOR UPDATE OF m
				""").param("student", studentId).query(TeamStore::member).optional();
	}

	/** An active membership of the Team by its ID, without a lock. */
	Optional<Member> activeMember(UUID teamId, UUID membershipId) {
		return this.jdbc.sql("""
				SELECT m.id, m.team_id, t.mentor_account_id, m.student_account_id, m.joined_at
				FROM team.membership m JOIN team.team t ON t.id = m.team_id
				WHERE m.id = :id AND m.team_id = :team AND m.left_at IS NULL
				""").param("id", membershipId).param("team", teamId).query(TeamStore::member).optional();
	}

	/** The membership a Crew row reserves, active or not. */
	Optional<UUID> reservedMembership(UUID crewRowId) {
		return this.jdbc.sql("SELECT membership_id FROM team.crew_reservation WHERE crew_row_id = :id")
			.param("id", crewRowId)
			.query(UUID.class)
			.optional();
	}

	Optional<Member> membershipById(UUID membershipId) {
		return this.jdbc.sql("""
				SELECT m.id, m.team_id, t.mentor_account_id, m.student_account_id, m.joined_at
				FROM team.membership m JOIN team.team t ON t.id = m.team_id
				WHERE m.id = :id AND m.left_at IS NULL
				""").param("id", membershipId).query(TeamStore::member).optional();
	}

	void reserve(UUID crewRowId, UUID membershipId, UUID jobId, Instant now) {
		this.jdbc.sql("""
				INSERT INTO team.crew_reservation (crew_row_id, membership_id, job_id, reserved_at)
				VALUES (:crew, :membership, :job, :now) ON CONFLICT (crew_row_id) DO NOTHING
				""")
			.param("crew", crewRowId)
			.param("membership", membershipId)
			.param("job", jobId)
			.param("now", Timestamp.from(now))
			.update();
	}

	void unreserve(UUID crewRowId) {
		this.jdbc.sql("DELETE FROM team.crew_reservation WHERE crew_row_id = :id").param("id", crewRowId).update();
	}

	/** Whether the membership has a Crew reservation; read under the membership's lock. */
	boolean reserved(UUID membershipId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM team.crew_reservation WHERE membership_id = :id)")
			.param("id", membershipId)
			.query(Boolean.class)
			.single();
	}

	/** Which of these memberships have a Crew reservation, for display. */
	Set<UUID> reservedAmong(Collection<UUID> membershipIds) {
		if (membershipIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(this.jdbc.sql("""
				SELECT DISTINCT membership_id FROM team.crew_reservation WHERE membership_id IN (:ids)
				""").param("ids", List.copyOf(membershipIds)).query(UUID.class).list());
	}

	void insertMembership(UUID teamId, UUID studentId, Instant now) {
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at)
				VALUES (:id, :team, :student, :now)
				""")
			.param("id", this.ids.newId())
			.param("team", teamId)
			.param("student", studentId)
			.param("now", Timestamp.from(now))
			.update();
	}

	void endMembership(UUID membershipId, String reason, Instant now) {
		this.jdbc.sql("UPDATE team.membership SET left_at = :now, left_reason = :reason WHERE id = :id AND left_at IS NULL")
			.param("id", membershipId)
			.param("reason", reason)
			.param("now", Timestamp.from(now))
			.update();
	}

	Request insertRequest(UUID teamId, UUID mentorId, UUID studentId, Kind kind, Instant now, Instant lapsesAt) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO team.team_request (id, team_id, student_account_id, kind, status, created_at, lapses_at)
				VALUES (:id, :team, :student, :kind, 'PENDING', :now, :lapses)
				""")
			.param("id", id)
			.param("team", teamId)
			.param("student", studentId)
			.param("kind", kind.name())
			.param("now", Timestamp.from(now))
			.param("lapses", Timestamp.from(lapsesAt))
			.update();
		return new Request(id, teamId, mentorId, studentId, kind, RequestStatus.PENDING, now, lapsesAt);
	}

	Optional<Request> request(UUID requestId) {
		return this.jdbc.sql("SELECT " + REQUEST_COLUMNS
				+ " FROM team.team_request r JOIN team.team t ON t.id = r.team_id WHERE r.id = :id")
			.param("id", requestId)
			.query(TeamStore::request)
			.optional();
	}

	/** Locks the request row; the caller holds the Team row first when it changes membership. */
	Optional<Request> lockRequest(UUID requestId) {
		return this.jdbc.sql("SELECT " + REQUEST_COLUMNS
				+ " FROM team.team_request r JOIN team.team t ON t.id = r.team_id WHERE r.id = :id FOR UPDATE OF r")
			.param("id", requestId)
			.query(TeamStore::request)
			.optional();
	}

	Optional<Request> pendingBetween(UUID teamId, UUID studentId) {
		return this.jdbc.sql("SELECT " + REQUEST_COLUMNS + """
				 FROM team.team_request r JOIN team.team t ON t.id = r.team_id
				WHERE r.team_id = :team AND r.student_account_id = :student AND r.status = 'PENDING'
				""").param("team", teamId).param("student", studentId).query(TeamStore::request).optional();
	}

	/** The Team's pending requests of {@code kind}, oldest first. */
	List<Request> pendingOfTeam(UUID teamId, Kind kind) {
		return this.jdbc.sql("SELECT " + REQUEST_COLUMNS + """
				 FROM team.team_request r JOIN team.team t ON t.id = r.team_id
				WHERE r.team_id = :team AND r.kind = :kind AND r.status = 'PENDING' ORDER BY r.created_at, r.id
				""").param("team", teamId).param("kind", kind.name()).query(TeamStore::request).list();
	}

	/** The Student's pending requests of every kind, oldest first. */
	List<Request> pendingOfStudent(UUID studentId) {
		return this.jdbc.sql("SELECT " + REQUEST_COLUMNS + """
				 FROM team.team_request r JOIN team.team t ON t.id = r.team_id
				WHERE r.student_account_id = :student AND r.status = 'PENDING' ORDER BY r.created_at, r.id
				""").param("student", studentId).query(TeamStore::request).list();
	}

	void answer(UUID requestId, RequestStatus status, Instant now) {
		this.jdbc.sql("UPDATE team.team_request SET status = :status, answered_at = :now WHERE id = :id AND status = 'PENDING'")
			.param("id", requestId)
			.param("status", status.name())
			.param("now", Timestamp.from(now))
			.update();
	}

	/**
	 * Cancels the Student's other pending requests, once they joined a Team or left the platform. A
	 * request another transaction holds is skipped, not waited for: two Teams accepting the same
	 * Student at once would otherwise wait on each other's request. The loser is refused with
	 * {@code STUDENT_IN_TEAM}, and its request stays pending until it lapses.
	 */
	void cancelPendingOfStudent(UUID studentId, @Nullable UUID except, Instant now) {
		this.jdbc.sql("""
				UPDATE team.team_request SET status = 'CANCELLED', answered_at = :now
				WHERE id IN (SELECT id FROM team.team_request
				             WHERE student_account_id = :student AND status = 'PENDING'
				               AND (CAST(:except AS UUID) IS NULL OR id <> :except)
				             FOR UPDATE SKIP LOCKED)
				""").param("student", studentId).param("except", except).param("now", Timestamp.from(now)).update();
	}

	void cancelPendingOfTeam(UUID teamId, Instant now) {
		this.jdbc.sql("""
				UPDATE team.team_request SET status = 'CANCELLED', answered_at = :now
				WHERE team_id = :team AND status = 'PENDING'
				""").param("team", teamId).param("now", Timestamp.from(now)).update();
	}

	/** How many invitations the Team sent since {@code since}. */
	int invitationsSince(UUID teamId, Instant since) {
		return this.jdbc.sql("""
				SELECT count(*) FROM team.team_request WHERE team_id = :team AND kind = 'INVITATION' AND created_at >= :since
				""").param("team", teamId).param("since", Timestamp.from(since)).query(Integer.class).single();
	}

	/** How many join requests the Student sent since {@code since}. */
	int joinRequestsSince(UUID studentId, Instant since) {
		return this.jdbc.sql("""
				SELECT count(*) FROM team.team_request
				WHERE student_account_id = :student AND kind = 'JOIN_REQUEST' AND created_at >= :since
				""").param("student", studentId).param("since", Timestamp.from(since)).query(Integer.class).single();
	}

	private static Team team(ResultSet rs, int n) throws SQLException {
		return new Team(rs.getObject("id", UUID.class), rs.getObject("mentor_account_id", UUID.class),
				Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant());
	}

	private static Member member(ResultSet rs, int n) throws SQLException {
		return new Member(rs.getObject("id", UUID.class), rs.getObject("team_id", UUID.class),
				rs.getObject("mentor_account_id", UUID.class), rs.getObject("student_account_id", UUID.class),
				rs.getTimestamp("joined_at").toInstant());
	}

	private static Request request(ResultSet rs, int n) throws SQLException {
		return new Request(rs.getObject("id", UUID.class), rs.getObject("team_id", UUID.class),
				rs.getObject("mentor_account_id", UUID.class), rs.getObject("student_account_id", UUID.class),
				Kind.valueOf(rs.getString("kind")), RequestStatus.valueOf(rs.getString("status")),
				rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("lapses_at").toInstant());
	}

}
