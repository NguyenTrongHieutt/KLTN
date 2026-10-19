package vn.vibefix.authz.fixtures;

import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;

import vn.vibefix.authz.Canaries;
import vn.vibefix.authz.MatrixFixture;
import vn.vibefix.authz.MatrixWorld;
import vn.vibefix.identity.MentorApprovalStatus;
import vn.vibefix.identity.internal.IdentityTestSupport;
import vn.vibefix.shared.Role;
import vn.vibefix.support.MutableClock;

/**
 * Mentor applications (FR-74): another Mentor's submitted application whose LinkedIn link is the
 * canary {@code CREW_LINKEDIN}, which no Mentor, Customer, or Student caller may receive; and a new
 * LinkedIn handle for each row ({@code {mentorHandle}}), since a submitted application reserves its
 * profile and rows share one database.
 */
public class MentorApplicationFixture implements MatrixFixture {

	private final JdbcClient jdbc;

	private final Canaries canaries = Canaries.load();

	public MentorApplicationFixture(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void seed(MatrixWorld world) {
		UUID other = IdentityTestSupport.insertAccount(this.jdbc, Role.MENTOR, MentorApprovalStatus.PENDING, null);
		Timestamp now = Timestamp.from(MutableClock.DEFAULT_INSTANT);
		this.jdbc.sql("""
				INSERT INTO identity.mentor_application
				    (account_id, display_name, experience, years_of_experience, skill_ids, skill_names, linkedin_url,
				     linkedin_hmac, linkedin_key_version, linkedin_reserved, privacy_notice_version, n22_version,
				     submitted_at, updated_at)
				VALUES (:account, 'Người hướng dẫn khác', 'Kinh nghiệm', 5,
				        ARRAY[CAST('0192b000-0000-7000-8001-000000000004' AS UUID)], 'React', :linkedin,
				        repeat('c', 64), 1, FALSE, '2.0', '1.0', :now, :now)
				""")
			.param("account", other)
			.param("linkedin", this.canaries.get("CREW_LINKEDIN").value())
			.param("now", now)
			.update();
		world.put("mentorHandle", "matrix-" + Long.toHexString(ThreadLocalRandom.current().nextLong()));
	}

}
