package vn.vibefix.identity.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.identity.ContactVisibility;

/**
 * Reads and writes the Customer and Student profiles, their showcased repositories, the theme, and
 * the contact grants (FR-3, FR-4, FR-51, UX-DR5).
 */
@Component
class Profiles implements ContactVisibility {

	/** "Sáng", "Tối" or "Theo hệ thống". */
	enum Theme {

		LIGHT, DARK, SYSTEM

	}

	enum CustomerKind {

		INDIVIDUAL, BUSINESS

	}

	record CustomerProfile(CustomerKind kind, String displayName, String contactEmail, @Nullable String phone) {
	}

	/** @param repositories {@code owner/name} references, in the order the Student gave */
	record StudentProfile(@Nullable String realName, @Nullable String school, @Nullable Integer yearOfStudy,
			@Nullable String linkedinUrl, boolean showNameAndSchool, List<String> repositories) {
	}

	private final JdbcClient jdbc;

	private final Clock clock;

	Profiles(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	Optional<CustomerProfile> customer(UUID accountId) {
		return this.jdbc.sql("SELECT * FROM identity.customer_profile WHERE account_id = :id")
			.param("id", accountId)
			.query((row, number) -> new CustomerProfile(CustomerKind.valueOf(row.getString("kind")),
					row.getString("display_name"), row.getString("contact_email"), row.getString("phone")))
			.optional();
	}

	@Transactional
	void saveCustomer(UUID accountId, CustomerProfile profile) {
		this.jdbc.sql("""
				INSERT INTO identity.customer_profile (account_id, kind, display_name, contact_email, phone, updated_at)
				VALUES (:id, :kind, :name, :email, :phone, :now)
				ON CONFLICT (account_id) DO UPDATE SET kind = EXCLUDED.kind, display_name = EXCLUDED.display_name,
				    contact_email = EXCLUDED.contact_email, phone = EXCLUDED.phone, updated_at = EXCLUDED.updated_at
				""")
			.param("id", accountId)
			.param("kind", profile.kind().name())
			.param("name", profile.displayName())
			.param("email", profile.contactEmail())
			.param("phone", profile.phone())
			.param("now", now())
			.update();
	}

	Optional<StudentProfile> student(UUID accountId) {
		List<String> repositories = this.jdbc.sql("""
				SELECT owner || '/' || name FROM identity.showcase_repository WHERE account_id = :id ORDER BY position
				""").param("id", accountId).query(String.class).list();
		return this.jdbc.sql("SELECT * FROM identity.student_profile WHERE account_id = :id")
			.param("id", accountId)
			.query((row, number) -> new StudentProfile(row.getString("real_name"), row.getString("school"),
					(Integer) row.getObject("year_of_study", Integer.class), row.getString("linkedin_url"),
					row.getBoolean("show_name_and_school"), repositories))
			.optional();
	}

	@Transactional
	void saveStudent(UUID accountId, StudentProfile profile) {
		this.jdbc.sql("""
				INSERT INTO identity.student_profile
				    (account_id, real_name, school, year_of_study, linkedin_url, show_name_and_school, updated_at)
				VALUES (:id, :realName, :school, :year, :linkedin, :show, :now)
				ON CONFLICT (account_id) DO UPDATE SET real_name = EXCLUDED.real_name, school = EXCLUDED.school,
				    year_of_study = EXCLUDED.year_of_study, linkedin_url = EXCLUDED.linkedin_url,
				    show_name_and_school = EXCLUDED.show_name_and_school, updated_at = EXCLUDED.updated_at
				""")
			.param("id", accountId)
			.param("realName", profile.realName())
			.param("school", profile.school())
			.param("year", profile.yearOfStudy())
			.param("linkedin", profile.linkedinUrl())
			.param("show", profile.showNameAndSchool())
			.param("now", now())
			.update();
		this.jdbc.sql("DELETE FROM identity.showcase_repository WHERE account_id = :id").param("id", accountId).update();
		for (int position = 0; position < profile.repositories().size(); position++) {
			String[] parts = profile.repositories().get(position).split("/", 2);
			this.jdbc.sql("""
					INSERT INTO identity.showcase_repository (account_id, position, owner, name)
					VALUES (:id, :position, :owner, :name)
					""")
				.param("id", accountId)
				.param("position", position)
				.param("owner", parts[0])
				.param("name", parts[1])
				.update();
		}
	}

	Theme theme(UUID accountId) {
		return this.jdbc.sql("SELECT theme FROM identity.account WHERE id = :id")
			.param("id", accountId)
			.query(String.class)
			.optional()
			.map(Theme::valueOf)
			.orElse(Theme.SYSTEM);
	}

	void saveTheme(UUID accountId, Theme theme) {
		this.jdbc.sql("UPDATE identity.account SET theme = :theme WHERE id = :id")
			.param("theme", theme.name())
			.param("id", accountId)
			.update();
	}

	/** Whether {@code mentorId} holds an open grant on {@code customerId}'s contact details. */
	boolean hasContactGrant(UUID customerId, UUID mentorId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM identity.contact_grant
				WHERE customer_account_id = :customer AND mentor_account_id = :mentor AND (ends_at IS NULL OR ends_at > :now)
				""")
			.param("customer", customerId)
			.param("mentor", mentorId)
			.param("now", now())
			.query(Long.class)
			.single() > 0;
	}

	@Override
	public void grant(UUID customerId, UUID mentorId, UUID jobId) {
		this.jdbc.sql("""
				INSERT INTO identity.contact_grant (customer_account_id, mentor_account_id, job_id, granted_at)
				VALUES (:customer, :mentor, :job, :now)
				ON CONFLICT (customer_account_id, mentor_account_id, job_id) DO NOTHING
				""").param("customer", customerId).param("mentor", mentorId).param("job", jobId).param("now", now()).update();
	}

	@Override
	public void end(UUID customerId, UUID mentorId, UUID jobId, Instant at) {
		this.jdbc.sql("""
				UPDATE identity.contact_grant SET ends_at = :at
				WHERE customer_account_id = :customer AND mentor_account_id = :mentor AND job_id = :job
				""")
			.param("at", Timestamp.from(at))
			.param("customer", customerId)
			.param("mentor", mentorId)
			.param("job", jobId)
			.update();
	}

	private Timestamp now() {
		return Timestamp.from(this.clock.instant());
	}

}
