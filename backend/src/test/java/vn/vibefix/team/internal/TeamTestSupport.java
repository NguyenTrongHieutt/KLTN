package vn.vibefix.team.internal;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.identity.internal.Role;
import vn.vibefix.support.MutableClock;

/** Mentors and Students with a profile, for the Team tests; nobody is approved since Story 19.7. */
public final class TeamTestSupport {

	public static final String REACT = "0192b000-0000-7000-8001-000000000004";

	public static final String SUPABASE = "0192b000-0000-7000-8001-000000000025";

	public static final String WEB_SECURITY = "0192b000-0000-7000-8001-000000000043";

	private TeamTestSupport() {
	}

	/** A Mentor with GitHub linked and a profile; returns the account ID. */
	public static UUID mentor(JdbcClient jdbc, String displayName, List<String> skillIds, String skillNames) {
		UUID id = IdentityTestSupport.insertAccount(jdbc, Role.MENTOR, null);
		mentorProfile(jdbc, id, displayName, skillIds, skillNames);
		return id;
	}

	/**
	 * A Google account with no role (Story 20.3), GitHub linked, and the profile {@link #mentor} gives: anyone may
	 * mentor a Job, so the Mentor directory finds it like any other. Returns the account ID.
	 */
	public static UUID googleMentor(JdbcClient jdbc, String displayName, List<String> skillIds, String skillNames) {
		UUID id = IdentityTestSupport.insertGoogleAccount(jdbc, "google-" + UUID.randomUUID(), "3.1");
		IdentityTestSupport.linkGitHub(jdbc, id);
		mentorProfile(jdbc, id, displayName, skillIds, skillNames);
		return id;
	}

	private static void mentorProfile(JdbcClient jdbc, UUID id, String displayName, List<String> skillIds,
			String skillNames) {
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.profile
				    (account_id, display_name, experience, years_of_experience, skill_ids, skill_names, updated_at)
				VALUES (:account, :name, 'Kỹ sư phần mềm 8 năm, hướng dẫn sinh viên làm ứng dụng web.', 8,
				        CAST(:skills AS UUID[]), :skillNames, :now)
				""")
			.param("account", id)
			.param("name", displayName)
			.param("skills", "{" + String.join(",", skillIds) + "}")
			.param("skillNames", skillNames)
			.param("now", now)
			.update();
	}

	/** The school of every Student {@link #student} verifies. */
	public static final String SCHOOL = "Đại học Bách khoa Hà Nội";

	/** The major every Student {@link #student} verifies declares. */
	public static final String MAJOR = "Công nghệ thông tin";

	/**
	 * A Student with a profile; verified until {@code verifiedUntil} when given, declaring {@code name} and
	 * {@link #MAJOR} at {@link #SCHOOL}, which the profile then holds as the verification's projection (Story
	 * 19.4). An unverified Student has no student details.
	 * @param name their declared full name, shown only when {@code shown} and verified
	 */
	public static UUID student(JdbcClient jdbc, @Nullable String name, boolean shown, List<String> skillIds,
			String skillNames, @Nullable LocalDate verifiedUntil) {
		UUID id = IdentityTestSupport.insertAccount(jdbc, Role.STUDENT, null);
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.profile (account_id, hide_student_details, skill_ids, skill_names, updated_at)
				VALUES (:account, :hidden, CAST(:skills AS UUID[]), :skillNames, :now)
				""")
			.param("account", id)
			.param("hidden", !shown)
			.param("skills", "{" + String.join(",", skillIds) + "}")
			.param("skillNames", skillNames)
			.param("now", now)
			.update();
		if (verifiedUntil != null) {
			IdentityTestSupport.verifyStudent(jdbc, id, verifiedUntil, SCHOOL, name, MAJOR);
		}
		return id;
	}

	public static String login(JdbcClient jdbc, UUID account) {
		return jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", account)
			.query(String.class).single();
	}

}
