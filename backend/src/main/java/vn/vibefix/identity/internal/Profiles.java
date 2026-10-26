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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.audit.AuditActor;
import vn.vibefix.audit.AuditEntry;
import vn.vibefix.audit.AuditLog;
import vn.vibefix.audit.AuditObject;
import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.ContactVisibility;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * The one profile of each account (FR-3, FR-4, FR-51, FR-72, Story 19.3): what the person typed, with
 * their typed links and showcased repositories, the theme, and the contact grants (UX-DR5, AD-3).
 * Nothing here is verified, and nothing here writes a verified fact (AD-42). Skills are catalog IDs
 * (AD-38): a save may keep IDs that became inactive, but adds only active ones, at most
 * {@value #MAX_SKILLS}, and writes their names for search.
 */
@Component
class Profiles implements ContactVisibility {

	static final String SAVED_AUDIT = "identity.profile_saved";

	/**
	 * The one name rule (Story 19.3) over {@code identity.account a} and {@code identity.profile p}: the
	 * display name, else the GitHub username, else the Google name.
	 */
	static final String NAME = "coalesce(p.display_name, '@' || a.github_login, a.google_name)";

	/** "Sáng", "Tối" or "Theo hệ thống". */
	enum Theme {

		LIGHT, DARK, SYSTEM

	}

	/** "Cá nhân" or "Doanh nghiệp". */
	enum ProfileKind {

		INDIVIDUAL, BUSINESS

	}

	/** A profile keeps at most 20 Skills (FR-4, FR-72). */
	static final int MAX_SKILLS = 20;

	/** A profile shows at most 5 typed links and 5 repositories (FR-4). */
	static final int MAX_LINKS = 5;

	/**
	 * The projection of the live student verification's facts (Story 19.4): the full name and major
	 * declared at verification and the school (the email's domain). Only the verification writes and
	 * clears them; the profile never edits them.
	 */
	record StudentDetails(@Nullable String name, @Nullable String school, @Nullable String major) {
	}

	/**
	 * The profile as saved; an account that never saved one has {@link #empty()}.
	 * @param skillIds catalog Skill IDs, in the order the person chose them
	 * @param links typed {@code https} links, "Tự khai", in order
	 * @param repositories {@code owner/name} references, in order
	 * @param studentDetails absent when no detail was ever given
	 */
	record Profile(@Nullable String displayName, ProfileKind kind, @Nullable String contactEmail, @Nullable String phone,
			@Nullable String experience, @Nullable Integer yearsOfExperience, List<UUID> skillIds, List<String> links,
			List<String> repositories, boolean hideStudentDetails, @Nullable StudentDetails studentDetails) {

		static Profile empty() {
			return new Profile(null, ProfileKind.INDIVIDUAL, null, null, null, null, List.of(), List.of(), List.of(), false,
					null);
		}

	}

	/** What a save of ACC-1 writes: every typed field, never a verified fact or a student detail. */
	record Changes(String displayName, ProfileKind kind, @Nullable String contactEmail, @Nullable String phone,
			@Nullable String experience, @Nullable Integer yearsOfExperience, List<UUID> skillIds, List<String> links,
			List<String> repositories, boolean hideStudentDetails) {
	}

	/** The contact details only the owner, Administrators, and a granted Mentor read (FR-3, AD-20). */
	record Contact(ProfileKind kind, @Nullable String displayName, @Nullable String contactEmail, @Nullable String phone) {
	}

	private final JdbcClient jdbc;

	private final Catalog catalog;

	private final Clock clock;

	private final AuditLog auditLog;

	Profiles(JdbcClient jdbc, Catalog catalog, Clock clock, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.clock = clock;
		this.auditLog = auditLog;
	}

	/** The saved profile, if the account ever saved one. */
	Optional<Profile> find(UUID accountId) {
		List<String> links = this.jdbc.sql("SELECT url FROM identity.profile_link WHERE account_id = :id ORDER BY position")
			.param("id", accountId)
			.query(String.class)
			.list();
		List<String> repositories = this.jdbc.sql("""
				SELECT owner || '/' || name FROM identity.showcase_repository WHERE account_id = :id ORDER BY position
				""").param("id", accountId).query(String.class).list();
		return this.jdbc.sql("SELECT * FROM identity.profile WHERE account_id = :id")
			.param("id", accountId)
			.query((row, number) -> {
				String name = row.getString("student_name");
				String school = row.getString("student_school");
				String major = row.getString("student_major");
				return new Profile(row.getString("display_name"), ProfileKind.valueOf(row.getString("kind")),
						row.getString("contact_email"), row.getString("phone"), row.getString("experience"),
						(Integer) row.getObject("years_of_experience", Integer.class), uuids(row.getArray("skill_ids")),
						links, repositories, row.getBoolean("hide_student_details"),
						name == null && school == null && major == null ? null : new StudentDetails(name, school, major));
			})
			.optional();
	}

	/** The saved profile, or the empty one. */
	Profile profile(UUID accountId) {
		return find(accountId).orElseGet(Profile::empty);
	}

	/** The profile's Skills with their current names, inactive ones included, in the person's order. */
	List<Skill> skills(List<UUID> skillIds) {
		return this.catalog.byIds(skillIds);
	}

	/**
	 * Saves ACC-1's typed fields, creating the row on the first save; the student details stay as
	 * they are. Needs no approval, and is audited (AD-8).
	 * @throws vn.vibefix.shared.ApiException {@code VALIDATION} with {@code SKILL_INACTIVE} or {@code SKILL_LIMIT}
	 */
	@Transactional
	void save(UUID accountId, Changes changes) {
		List<UUID> skillIds = checkedSkills(accountId, changes.skillIds());
		this.jdbc.sql("""
				INSERT INTO identity.profile
				    (account_id, display_name, kind, contact_email, phone, experience, years_of_experience, skill_ids,
				     skill_names, hide_student_details, updated_at)
				VALUES (:id, :name, :kind, :email, :phone, :experience, :years, CAST(:skills AS UUID[]), :names, :hide, :now)
				ON CONFLICT (account_id) DO UPDATE SET display_name = EXCLUDED.display_name, kind = EXCLUDED.kind,
				    contact_email = EXCLUDED.contact_email, phone = EXCLUDED.phone, experience = EXCLUDED.experience,
				    years_of_experience = EXCLUDED.years_of_experience, skill_ids = EXCLUDED.skill_ids,
				    skill_names = EXCLUDED.skill_names, hide_student_details = EXCLUDED.hide_student_details,
				    updated_at = EXCLUDED.updated_at
				""")
			.param("id", accountId)
			.param("name", changes.displayName())
			.param("kind", changes.kind().name())
			.param("email", changes.contactEmail())
			.param("phone", changes.phone())
			.param("experience", changes.experience())
			.param("years", changes.yearsOfExperience())
			.param("skills", arrayLiteral(skillIds))
			.param("names", skillNames(skillIds))
			.param("hide", changes.hideStudentDetails())
			.param("now", now())
			.update();
		// A typed link is "Tự khai": it proves nothing, so it records no person-link signal (Story 19.3).
		this.jdbc.sql("DELETE FROM identity.profile_link WHERE account_id = :id").param("id", accountId).update();
		for (int position = 0; position < changes.links().size(); position++) {
			this.jdbc.sql("INSERT INTO identity.profile_link (account_id, position, url) VALUES (:id, :position, :url)")
				.param("id", accountId)
				.param("position", position)
				.param("url", changes.links().get(position))
				.update();
		}
		this.jdbc.sql("DELETE FROM identity.showcase_repository WHERE account_id = :id").param("id", accountId).update();
		for (int position = 0; position < changes.repositories().size(); position++) {
			String[] parts = changes.repositories().get(position).split("/", 2);
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
		this.auditLog.record(AuditEntry.of(AuditActor.user(accountId), SAVED_AUDIT,
				AuditObject.of("identity.account", accountId)));
	}

	/**
	 * Each Skill once, in order; the IDs the profile did not hold yet must be active, and no more than
	 * {@value #MAX_SKILLS} in all. Locks the profile row when there is one, so two later saves of one
	 * account run one at a time; a first save has no row to lock, and the upsert keeps one row.
	 */
	private List<UUID> checkedSkills(UUID accountId, List<UUID> requested) {
		List<UUID> skillIds = List.copyOf(new LinkedHashSet<>(requested));
		this.catalog.requireAtMost(skillIds.size(), MAX_SKILLS);
		Set<UUID> held = Set.copyOf(this.jdbc.sql("""
				SELECT skill_ids FROM identity.profile WHERE account_id = :id FOR UPDATE
				""").param("id", accountId).query((row, number) -> uuids(row.getArray("skill_ids"))).optional().orElse(List.of()));
		this.catalog.requireActive(skillIds.stream().filter(id -> !held.contains(id)).toList());
		return skillIds;
	}

	/**
	 * Rewrites {@code skill_names} on every profile holding {@code skillId}, after a rename (AD-38); the
	 * caller's transaction also records the event as processed, so a replay rewrites nothing.
	 */
	int refreshSkillNames(UUID skillId) {
		record Holder(UUID accountId, List<UUID> skillIds) {
		}
		List<Holder> holders = this.jdbc.sql("""
				SELECT account_id, skill_ids FROM identity.profile
				WHERE skill_ids @> ARRAY[CAST(:skill AS UUID)] FOR UPDATE
				""")
			.param("skill", skillId)
			.query((row, number) -> new Holder(row.getObject("account_id", UUID.class), uuids(row.getArray("skill_ids"))))
			.list();
		for (Holder holder : holders) {
			this.jdbc.sql("UPDATE identity.profile SET skill_names = :names WHERE account_id = :id")
				.param("id", holder.accountId())
				.param("names", skillNames(holder.skillIds()))
				.update();
		}
		return holders.size();
	}

	/** A {@code VALIDATION} refusal naming its fields with their constraint codes, as bean validation does. */
	static ApiException invalid(Map<String, String> codes) {
		List<Map<String, String>> errors = codes.entrySet()
			.stream()
			.map(entry -> Map.of("field", entry.getKey(), "code", entry.getValue()))
			.toList();
		return new ApiException(ErrorCode.VALIDATION, null, Map.<String, Object>of("errors", errors));
	}

	/** The contact details, once the account saved a profile. */
	Optional<Contact> contact(UUID accountId) {
		return this.jdbc.sql("SELECT kind, display_name, contact_email, phone FROM identity.profile WHERE account_id = :id")
			.param("id", accountId)
			.query((row, number) -> new Contact(ProfileKind.valueOf(row.getString("kind")), row.getString("display_name"),
					row.getString("contact_email"), row.getString("phone")))
			.optional();
	}

	/** The names search reads (AD-36): the Skills' current names, in order, each followed by a comma and a space but the last. */
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
