package vn.vibefix.identity.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.ContactVisibility;

/**
 * Reads and writes the Customer and Student profiles, their showcased repositories and Skills, the
 * theme, and the contact grants (FR-3, FR-4, FR-51, UX-DR5). Skills are catalog IDs (AD-38): a save
 * may keep IDs that became inactive, but adds only active ones, at most {@value #MAX_SKILLS}, and
 * writes their names for search.
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

	/** A Student or Mentor profile keeps at most 20 Skills (FR-4, FR-74). */
	static final int MAX_SKILLS = 20;

	/**
	 * @param repositories {@code owner/name} references, in the order the Student gave
	 * @param skillIds catalog Skill IDs, in the order the Student chose them
	 */
	record StudentProfile(@Nullable String realName, @Nullable String school, @Nullable Integer yearOfStudy,
			@Nullable String linkedinUrl, boolean showNameAndSchool, List<String> repositories, List<UUID> skillIds) {
	}

	private final JdbcClient jdbc;

	private final Catalog catalog;

	private final Clock clock;

	private final JdbcPersonSignals signals;

	Profiles(JdbcClient jdbc, Catalog catalog, Clock clock, JdbcPersonSignals signals) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.clock = clock;
		this.signals = signals;
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
					row.getBoolean("show_name_and_school"), repositories, uuids(row.getArray("skill_ids"))))
			.optional();
	}

	/** The profile's Skills with their current names, inactive ones included, in the Student's order. */
	List<Skill> skills(List<UUID> skillIds) {
		return this.catalog.byIds(skillIds);
	}

	/**
	 * Saves the profile with its Skills: each one once, in order; the IDs it did not hold yet must be
	 * active, and no more than {@value #MAX_SKILLS} in all.
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION} with {@code SKILL_INACTIVE} or {@code SKILL_LIMIT}
	 */
	@Transactional
	void saveStudent(UUID accountId, StudentProfile profile) {
		List<UUID> skillIds = List.copyOf(new LinkedHashSet<>(profile.skillIds()));
		this.catalog.requireAtMost(skillIds.size(), MAX_SKILLS);
		Set<UUID> held = Set.copyOf(this.jdbc.sql("""
				SELECT skill_ids FROM identity.student_profile WHERE account_id = :id FOR UPDATE
				""").param("id", accountId).query((row, number) -> uuids(row.getArray("skill_ids"))).optional().orElse(List.of()));
		this.catalog.requireActive(skillIds.stream().filter(id -> !held.contains(id)).toList());
		this.jdbc.sql("""
				INSERT INTO identity.student_profile
				    (account_id, real_name, school, year_of_study, linkedin_url, show_name_and_school, skill_ids,
				     skill_names, updated_at)
				VALUES (:id, :realName, :school, :year, :linkedin, :show, CAST(:skills AS UUID[]), :names, :now)
				ON CONFLICT (account_id) DO UPDATE SET real_name = EXCLUDED.real_name, school = EXCLUDED.school,
				    year_of_study = EXCLUDED.year_of_study, linkedin_url = EXCLUDED.linkedin_url,
				    show_name_and_school = EXCLUDED.show_name_and_school, skill_ids = EXCLUDED.skill_ids,
				    skill_names = EXCLUDED.skill_names, updated_at = EXCLUDED.updated_at
				""")
			.param("id", accountId)
			.param("realName", profile.realName())
			.param("school", profile.school())
			.param("year", profile.yearOfStudy())
			.param("linkedin", profile.linkedinUrl())
			.param("show", profile.showNameAndSchool())
			.param("skills", arrayLiteral(skillIds))
			.param("names", skillNames(skillIds))
			.param("now", now())
			.update();
		if (profile.linkedinUrl() != null) {
			this.signals.record(accountId, JdbcPersonSignals.Source.LINKEDIN, profile.linkedinUrl());
		}
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

	/**
	 * Rewrites {@code skill_names} on every Student profile holding {@code skillId}, after a rename
	 * (AD-38); the caller's transaction also records the event as processed.
	 */
	int refreshSkillNames(UUID skillId) {
		record Holder(UUID accountId, List<UUID> skillIds) {
		}
		List<Holder> holders = this.jdbc.sql("""
				SELECT account_id, skill_ids FROM identity.student_profile
				WHERE skill_ids @> ARRAY[CAST(:skill AS UUID)] FOR UPDATE
				""")
			.param("skill", skillId)
			.query((row, number) -> new Holder(row.getObject("account_id", UUID.class), uuids(row.getArray("skill_ids"))))
			.list();
		for (Holder holder : holders) {
			this.jdbc.sql("UPDATE identity.student_profile SET skill_names = :names WHERE account_id = :id")
				.param("id", holder.accountId())
				.param("names", skillNames(holder.skillIds()))
				.update();
		}
		return holders.size();
	}

	/** Rewrites {@code skill_names} on every Mentor application holding {@code skillId}, after a rename (FR-72). */
	int refreshMentorSkillNames(UUID skillId) {
		record Holder(UUID accountId, List<UUID> skillIds) {
		}
		List<Holder> holders = this.jdbc.sql("""
				SELECT account_id, skill_ids FROM identity.mentor_application
				WHERE skill_ids @> ARRAY[CAST(:skill AS UUID)] FOR UPDATE
				""")
			.param("skill", skillId)
			.query((row, number) -> new Holder(row.getObject("account_id", UUID.class), uuids(row.getArray("skill_ids"))))
			.list();
		for (Holder holder : holders) {
			this.jdbc.sql("UPDATE identity.mentor_application SET skill_names = :names WHERE account_id = :id")
				.param("id", holder.accountId())
				.param("names", skillNames(holder.skillIds()))
				.update();
		}
		return holders.size();
	}

	/** The names search reads (AD-36): the Skills' current names, in order, joined by ", ". */
	private String skillNames(List<UUID> skillIds) {
		return this.catalog.byIds(skillIds).stream().map(Skill::name).collect(Collectors.joining(", "));
	}

	/** A PostgreSQL array literal of UUIDs, which have no character that needs quoting. */
	private static String arrayLiteral(List<UUID> ids) {
		return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
	}

	private static List<UUID> uuids(@Nullable Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		return new ArrayList<>(Arrays.asList((UUID[]) array.getArray()));
	}

	Theme theme(UUID accountId) {
		return this.jdbc.sql("SELECT theme FROM identity.account WHERE id = :id")
			.param("id", accountId)
			.query(String.class)
			.optional()
			.map(Theme::valueOf)
			.orElse(Theme.LIGHT);
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
