package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.identity.PublicProfiles;
import vn.vibefix.identity.internal.Profiles.Profile;

/**
 * {@link PublicProfiles} over {@code identity.account}, {@code identity.profile}, and the live student verification
 * (Story 21.3): the GitHub fact is the link (its month the last link), the student facts are the live verification
 * while it is current today in Vietnam, and LinkedIn reads "Chưa xác minh" until Story 19.6. The student's details
 * come from the verification and show only while the owner does not hide them (X9); the school email is stored
 * nowhere (AD-42). A username is the stored {@code github_login} of a linked person's account, which every link
 * refreshes; it has no unique index, so two accounts holding it find no one.
 */
@Component
class JdbcPublicProfiles implements PublicProfiles {

	private static final String COLUMNS = " a.id, a.github_login, a.github_user_id,"
			+ " coalesce(a.github_linked_at, a.created_at) AS github_since, a.locked_at IS NOT NULL AS locked, a.created_at,"
			+ " coalesce(" + Profiles.NAME + ", '') AS display_name, p.years_of_experience,"
			+ " coalesce(p.skill_ids, '{}') AS skill_ids, coalesce(p.hide_student_details, FALSE) AS hidden,"
			+ " v.verified_at, v.valid_until, v.facts ->> 'school' AS school, v.facts ->> 'name' AS student_name,"
			+ " v.facts ->> 'major' AS major ";

	private static final String FROM = """
			FROM identity.account a
			LEFT JOIN identity.profile p ON p.account_id = a.id
			LEFT JOIN identity.verification v ON v.account_id = a.id AND v.kind = 'STUDENT' AND v.ended_at IS NULL
			WHERE a.kind = 'USER' AND a.erased_at IS NULL
			""";

	/** A person found by username: a linked account of a person, not erased. */
	private static final String LINKED_LOGIN = " AND " + Accounts.GITHUB_LINKED + " AND lower(a.github_login) = lower(:login)";

	private final JdbcClient jdbc;

	private final Profiles profiles;

	private final Catalog catalog;

	private final Clock clock;

	JdbcPublicProfiles(JdbcClient jdbc, Profiles profiles, Catalog catalog, Clock clock) {
		this.jdbc = jdbc;
		this.profiles = profiles;
		this.catalog = catalog;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PublicPerson> byUsername(String username) {
		List<PersonFacts> found = this.jdbc.sql("SELECT " + COLUMNS + FROM + LINKED_LOGIN + " LIMIT 2")
			.param("login", username.strip())
			.query((rs, n) -> facts(rs, today()))
			.list();
		if (found.size() != 1) {
			return Optional.empty();
		}
		PersonFacts facts = found.getFirst();
		Profile profile = this.profiles.profile(facts.accountId());
		return Optional.of(new PublicPerson(facts, profile.experience(), profile.links(), profile.repositories(),
				SkillGroups.of(this.catalog, this.catalog.byIds(profile.skillIds()))));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, UUID> unlockedAccountsOf(Collection<String> usernames) {
		Map<String, UUID> accounts = new LinkedHashMap<>();
		for (String username : new LinkedHashSet<>(usernames)) {
			record Holder(UUID id, boolean locked) {
			}
			// Two accounts with one username find no one, as PUB-4 does, a locked one among them included.
			List<Holder> found = this.jdbc.sql("SELECT a.id, a.locked_at IS NOT NULL AS locked FROM identity.account a"
					+ " WHERE a.kind = 'USER' AND a.erased_at IS NULL" + LINKED_LOGIN + " LIMIT 2")
				.param("login", username.strip())
				.query((rs, n) -> new Holder(rs.getObject("id", UUID.class), rs.getBoolean("locked")))
				.list();
			if (found.size() == 1 && !found.getFirst().locked()) {
				accounts.put(username, found.getFirst().id());
			}
		}
		return accounts;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, PersonFacts> factsOf(Collection<UUID> accountIds) {
		Map<UUID, PersonFacts> facts = new LinkedHashMap<>();
		if (accountIds.isEmpty()) {
			return facts;
		}
		List<UUID> ids = List.copyOf(new LinkedHashSet<>(accountIds));
		LocalDate today = today();
		Map<UUID, PersonFacts> byId = new HashMap<>();
		this.jdbc.sql("SELECT " + COLUMNS + FROM + " AND a.id IN (:ids)").param("ids", ids).query(rs -> {
			PersonFacts found = facts(rs, today);
			byId.put(found.accountId(), found);
		});
		for (UUID id : ids) {
			PersonFacts found = byId.get(id);
			if (found != null) {
				facts.put(id, found);
			}
		}
		return facts;
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM);
	}

	private static PersonFacts facts(ResultSet rs, LocalDate today) throws SQLException {
		String login = rs.getString("github_login");
		boolean linked = rs.getObject("github_user_id") != null && login != null;
		List<VerifiedFact> verified = new ArrayList<>();
		verified.add(new VerifiedFact(FactKind.GITHUB, linked ? instant(rs, "github_since") : null, linked ? login : null,
				null, null, null));
		LocalDate validUntil = rs.getObject("valid_until", LocalDate.class);
		Instant verifiedAt = instant(rs, "verified_at");
		// A lapsed verification is "Chưa xác minh" at once, though its expiry Deadline has yet to end it.
		boolean student = verifiedAt != null && validUntil != null && !today.isAfter(validUntil);
		boolean shown = student && !rs.getBoolean("hidden");
		verified.add(new VerifiedFact(FactKind.STUDENT, student ? verifiedAt : null, null,
				shown ? rs.getString("school") : null, shown ? rs.getString("student_name") : null,
				shown ? rs.getString("major") : null));
		verified.add(new VerifiedFact(FactKind.LINKEDIN, null, null, null, null, null));
		return new PersonFacts(rs.getObject("id", UUID.class), rs.getString("display_name"), linked ? login : null,
				rs.getObject("years_of_experience", Integer.class), rs.getTimestamp("created_at").toInstant(), verified,
				JdbcStudentDirectory.uuids(rs.getArray("skill_ids")), rs.getBoolean("locked"));
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
