package vn.vibefix.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.identity.MentorDirectory;
import vn.vibefix.identity.SkillGroup;
import vn.vibefix.shared.KeywordQuery;

/**
 * Mentors' public fields from their profiles (FR-51, AD-36, Story 19.3): every person's account ({@code USER}
 * kind) that has a profile and is not erased, whatever role it holds in any Job (Story 20.3); nobody approves
 * them (Story 19.7). A Mentor is available, to be invited, recommended, or granted, while unlocked with GitHub
 * linked. Search finds the available ones only, the GitHub-verified, unlocked users (AD-36, Story 21.3); the
 * username lookup also finds a locked one, as unavailable.
 */
@Component
class JdbcMentorDirectory implements MentorDirectory {

	/** Unlocked, not erased, and with GitHub linked: a Mentor who may take new work, as the gates read it (Q8). */
	private static final String AVAILABLE = "(" + Accounts.ACTIVE + " AND " + Accounts.GITHUB_LINKED + ")";

	private static final String COLUMNS = " a.id, a.github_login, a.github_user_id, a.created_at, coalesce(" + Profiles.NAME
			+ ", '') AS display_name, coalesce(p.experience, '') AS experience, p.years_of_experience, p.skill_ids,"
			+ " " + AVAILABLE + " AS available ";

	private static final String FROM = """
			FROM identity.account a JOIN identity.profile p ON p.account_id = a.id
			WHERE a.kind = 'USER' AND a.erased_at IS NULL
			""";

	/** The username lookup finds a Mentor with no profile too: their name is their GitHub username, nothing else declared. */
	private static final String FROM_ANY = """
			FROM identity.account a LEFT JOIN identity.profile p ON p.account_id = a.id
			WHERE a.kind = 'USER' AND a.erased_at IS NULL
			""";

	private record Row(UUID id, @Nullable String login, @Nullable Long githubUserId, Instant createdAt, String displayName,
			String experience, @Nullable Integer years, List<UUID> skillIds, boolean available) {
	}

	private final JdbcClient jdbc;

	private final Catalog catalog;

	JdbcMentorDirectory(JdbcClient jdbc, Catalog catalog) {
		this.jdbc = jdbc;
		this.catalog = catalog;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<MentorCard> search(String query, Collection<UUID> skillIds, @Nullable UUID excluded, Pageable pageable) {
		Optional<KeywordQuery> keyword = KeywordQuery.of(query, "p.search_vector");
		Map<String, Object> params = new HashMap<>();
		// FR-14, AD-36 (Story 21.3): GitHub-verified, unlocked users only; an unlinked one is not listed at all.
		StringBuilder where = new StringBuilder(FROM).append(" AND ").append(AVAILABLE);
		if (excluded != null) {
			where.append(" AND a.id <> :excluded");
			params.put("excluded", excluded);
		}
		keyword.ifPresent(found -> {
			where.append(" AND ").append(found.condition());
			params.putAll(found.params());
		});
		if (!skillIds.isEmpty()) {
			where.append(" AND p.skill_ids && CAST(:skills AS UUID[])");
			params.put("skills", JdbcStudentDirectory.uuidArray(skillIds));
		}
		// FR-14: a keyword orders by relevance alone, then the account ID; the Skill filter alone by name.
		String order = keyword.map(found -> found.rank("p.search_vector") + " DESC, a.id")
			.orElse("p.display_name, a.id");
		List<Row> rows = this.jdbc.sql("SELECT " + COLUMNS + where + " ORDER BY " + order + " LIMIT :size OFFSET :offset")
			.params(params)
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query(JdbcMentorDirectory::row)
			.list();
		long total = this.jdbc.sql("SELECT count(*) " + where).params(params).query(Long.class).single();
		Map<UUID, String> names = names(rows);
		return new PageImpl<>(rows.stream().map(row -> card(row, names)).toList(), pageable, total);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MentorProfile> byLogin(String githubLogin) {
		Optional<Row> found = this.jdbc.sql("SELECT " + COLUMNS + FROM_ANY + " AND lower(a.github_login) = lower(:login)")
			.param("login", githubLogin.strip())
			.query(JdbcMentorDirectory::row)
			.optional();
		return found.map(row -> {
			List<Skill> skills = this.catalog.byIds(row.skillIds());
			Map<UUID, String> names = skills.stream().collect(Collectors.toMap(Skill::id, Skill::name));
			return new MentorProfile(card(row, names), row.experience(), row.createdAt(), SkillGroups.of(this.catalog, skills));
		});
	}

	@Override
	@Transactional(readOnly = true)
	public List<MentorCard> cards(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		List<Row> rows = this.jdbc.sql("SELECT " + COLUMNS + FROM + " AND a.id IN (:ids)")
			.param("ids", List.copyOf(ids))
			.query(JdbcMentorDirectory::row)
			.list();
		Map<UUID, String> names = names(rows);
		Map<UUID, MentorCard> byId = rows.stream()
			.map(row -> card(row, names))
			.collect(Collectors.toMap(MentorCard::accountId, Function.identity()));
		List<MentorCard> ordered = new ArrayList<>();
		for (UUID id : ids) {
			MentorCard card = byId.get(id);
			if (card != null) {
				ordered.add(card);
			}
		}
		return ordered;
	}

	@Override
	@Transactional(readOnly = true)
	public List<MentorSkills> holdingAny(Collection<UUID> skillIds, int limit) {
		if (skillIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("SELECT a.id, p.skill_ids " + FROM + " AND " + AVAILABLE + """
				 AND p.skill_ids && CAST(:skills AS UUID[])
				ORDER BY cardinality(ARRAY(SELECT unnest(p.skill_ids) INTERSECT SELECT unnest(CAST(:skills AS UUID[])))) DESC,
				  a.id
				LIMIT :limit
				""")
			.param("skills", JdbcStudentDirectory.uuidArray(skillIds))
			.param("limit", limit)
			.query((rs, n) -> new MentorSkills(rs.getObject("id", UUID.class),
					JdbcStudentDirectory.uuids(rs.getArray("skill_ids"))))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, List<UUID>> skillIdsOf(Collection<UUID> mentorIds) {
		Map<UUID, List<UUID>> skills = new HashMap<>();
		if (mentorIds.isEmpty()) {
			return skills;
		}
		this.jdbc.sql("SELECT a.id, p.skill_ids " + FROM + " AND " + AVAILABLE + " AND a.id IN (:ids)")
			.param("ids", List.copyOf(mentorIds))
			.query(rs -> {
				skills.put(rs.getObject("id", UUID.class), JdbcStudentDirectory.uuids(rs.getArray("skill_ids")));
			});
		return skills;
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> profileSkillIds(UUID mentorId) {
		return this.jdbc.sql("SELECT p.skill_ids " + FROM + " AND a.id = :id")
			.param("id", mentorId)
			.query((rs, n) -> JdbcStudentDirectory.uuids(rs.getArray("skill_ids")))
			.optional()
			.orElse(List.of());
	}

	@Override
	@Transactional(readOnly = true)
	public boolean hasProfileName(UUID mentorId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 " + FROM + " AND a.id = :id AND p.display_name IS NOT NULL)")
			.param("id", mentorId)
			.query(Boolean.class)
			.single();
	}

	private Map<UUID, String> names(List<Row> rows) {
		Map<UUID, String> names = new HashMap<>();
		for (Skill skill : this.catalog.byIds(rows.stream().flatMap(row -> row.skillIds().stream()).distinct().toList())) {
			names.put(skill.id(), skill.name());
		}
		return names;
	}

	private static MentorCard card(Row row, Map<UUID, String> names) {
		return new MentorCard(row.id(), row.login(), row.githubUserId(), row.displayName(), row.years(),
				row.skillIds().stream().map(names::get).filter(name -> name != null).toList(), row.available());
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("github_login"), rs.getObject("github_user_id", Long.class),
				rs.getTimestamp("created_at").toInstant(), rs.getString("display_name"), rs.getString("experience"),
				rs.getObject("years_of_experience", Integer.class),
				JdbcStudentDirectory.uuids(rs.getArray("skill_ids")),
				rs.getBoolean("available"));
	}

}
