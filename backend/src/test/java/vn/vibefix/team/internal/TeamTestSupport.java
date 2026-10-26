package vn.vibefix.team.internal;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/** Mentors with a profile and a submitted application, and Students with a profile, for the Team tests. */
public final class TeamTestSupport {

	public static final String REACT = "0192b000-0000-7000-8001-000000000004";

	public static final String SUPABASE = "0192b000-0000-7000-8001-000000000025";

	public static final String WEB_SECURITY = "0192b000-0000-7000-8001-000000000043";

	private TeamTestSupport() {
	}

	/** A Mentor in {@code status} with a submitted application; returns the account ID. */
	public static UUID mentor(JdbcClient jdbc, MentorApprovalStatus status, String displayName, List<String> skillIds,
			String skillNames) {
		UUID id = IdentityTestSupport.insertAccount(jdbc, Role.MENTOR, status, null);
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
		jdbc.sql("""
				INSERT INTO identity.mentor_application
				    (account_id, linkedin_url, linkedin_hmac, linkedin_key_version, linkedin_reserved, privacy_notice_version,
				     n22_version, submitted_at, updated_at)
				VALUES (:account, :linkedin, :hmac, 1, TRUE, '2.0', '1.0', :now, :now)
				""")
			.param("account", id)
			.param("linkedin", "https://www.linkedin.com/in/m-" + id.toString().substring(0, 8))
			.param("hmac", id.toString().replace("-", "") + id.toString().replace("-", ""))
			.param("now", now)
			.update();
		return id;
	}

	/**
	 * A Student with a profile; verified until {@code verifiedUntil} when given.
	 * @param name their real name, shown only when {@code shown}
	 */
	public static UUID student(JdbcClient jdbc, @Nullable String name, boolean shown, List<String> skillIds,
			String skillNames, @Nullable LocalDate verifiedUntil) {
		UUID id = IdentityTestSupport.insertAccount(jdbc, Role.STUDENT, null, null);
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		jdbc.sql("""
				INSERT INTO identity.profile
				    (account_id, student_name, student_school, student_year, hide_student_details, skill_ids, skill_names,
				     updated_at)
				VALUES (:account, :name, 'Đại học Bách khoa Hà Nội', 3, :hidden, CAST(:skills AS UUID[]), :skillNames, :now)
				""")
			.param("account", id)
			.param("name", name)
			.param("hidden", !shown)
			.param("skills", "{" + String.join(",", skillIds) + "}")
			.param("skillNames", skillNames)
			.param("now", now)
			.update();
		if (verifiedUntil != null) {
			IdentityTestSupport.verifyStudent(jdbc, id, verifiedUntil);
		}
		return id;
	}

	public static String login(JdbcClient jdbc, UUID account) {
		return jdbc.sql("SELECT github_login FROM identity.account WHERE id = :id").param("id", account)
			.query(String.class).single();
	}

}
