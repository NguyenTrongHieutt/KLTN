package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixCaller;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.MutableClock;
import vn.vibefix.team.internal.TeamTestSupport;

/**
 * Teams (FR-6, AD-37): another Mentor's Team ({@code {teamMentorId}}, {@code {username}}) with a
 * member who hides their name, the canary {@code STUDENT_HIDDEN_NAME} ({@code {studentId}}), a
 * pending join request ({@code {joinRequestId}}), a pending invitation ({@code {invitationId}}), and a
 * verified Student in no Team ({@code {freeStudentId}}). It realizes {@link MatrixCaller#TEAM_MENTOR} as
 * that Team's Mentor and {@link MatrixCaller#TEAM_MEMBER} as the member ({@code {membershipId}}).
 */
public class TeamFixture implements MatrixFixture {

	private static final LocalDate VERIFIED_UNTIL = LocalDate.of(2026, 9, 30);

	private final JdbcClient jdbc;

	private final SessionRepository<? extends Session> sessions;

	private final Canaries canaries = Canaries.load();

	private UUID mentor;

	private UUID member;

	public TeamFixture(JdbcClient jdbc, SessionRepository<? extends Session> sessions) {
		this.jdbc = jdbc;
		this.sessions = sessions;
	}

	@Override
	public void seed(MatrixWorld world) {
		this.mentor = TeamTestSupport.mentor(this.jdbc, "Người hướng dẫn ma trận",
				List.of(TeamTestSupport.REACT), "React");
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		Timestamp lapses = Timestamp.from(MutableClock.DEFAULT_INSTANT.plus(Duration.ofDays(14)));
		UUID team = UUID.randomUUID();
		this.jdbc.sql("INSERT INTO team.team (id, mentor_account_id, status, created_at) VALUES (:id, :mentor, 'OPEN', :now)")
			.param("id", team)
			.param("mentor", this.mentor)
			.param("now", now)
			.update();
		UUID member = TeamTestSupport.student(this.jdbc, this.canaries.get("STUDENT_HIDDEN_NAME").value(), false,
				List.of(TeamTestSupport.REACT), "React", VERIFIED_UNTIL);
		UUID membership = UUID.randomUUID();
		this.jdbc.sql("""
				INSERT INTO team.membership (id, team_id, student_account_id, joined_at) VALUES (:id, :team, :student, :now)
				""").param("id", membership).param("team", team).param("student", member).param("now", now).update();
		this.member = member;
		UUID requesting = TeamTestSupport.student(this.jdbc, "Sinh viên xin vào", true, List.of(TeamTestSupport.REACT),
				"React", VERIFIED_UNTIL);
		UUID invited = TeamTestSupport.student(this.jdbc, "Sinh viên được mời", true, List.of(TeamTestSupport.REACT),
				"React", VERIFIED_UNTIL);
		UUID joinRequest = UUID.randomUUID();
		UUID invitation = UUID.randomUUID();
		for (Object[] request : List.of(new Object[] { joinRequest, requesting, "JOIN_REQUEST" },
				new Object[] { invitation, invited, "INVITATION" })) {
			this.jdbc.sql("""
					INSERT INTO team.team_request (id, team_id, student_account_id, kind, status, created_at, lapses_at)
					VALUES (:id, :team, :student, :kind, 'PENDING', :now, :lapses)
					""")
				.param("id", request[0])
				.param("team", team)
				.param("student", request[1])
				.param("kind", request[2])
				.param("now", now)
				.param("lapses", lapses)
				.update();
		}
		UUID free = TeamTestSupport.student(this.jdbc, "Sinh viên tự do", true, List.of(TeamTestSupport.REACT), "React",
				VERIFIED_UNTIL);
		world.put("teamMentorId", this.mentor);
		world.put("username", TeamTestSupport.login(this.jdbc, this.mentor));
		world.put("studentId", member);
		world.put("studentLogin", TeamTestSupport.login(this.jdbc, member));
		world.put("joinRequestId", joinRequest);
		world.put("invitationId", invitation);
		world.put("freeStudentId", free);
		world.put("membershipId", membership);
	}

	@Override
	public Set<String> stateKeys() {
		return Set.of();
	}

	@Override
	public Optional<RequestPostProcessor> realize(MatrixCaller caller, Map<String, String> state, MatrixWorld world) {
		return switch (caller) {
			case TEAM_MENTOR -> Optional.of(IdentityTestSupport.signedIn(this.sessions, this.mentor, Role.MENTOR,
					MutableClock.DEFAULT_INSTANT));
			case TEAM_MEMBER -> Optional.of(IdentityTestSupport.signedIn(this.sessions, this.member, Role.STUDENT,
					MutableClock.DEFAULT_INSTANT));
			default -> Optional.empty();
		};
	}

}
