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
 * Mentors' public fields from their submitted applications (FR-51, AD-36). Search finds approved,
 * unlocked, unerased Mentors only; PUB-5 also shows a revoked or locked Mentor, as unavailable.
 */
@Component
class JdbcMentorDirectory implements MentorDirectory {

	private static final String COLUMNS = """
			a.id, a.github_login, a.created_at, m.display_name, m.experience, m.years_of_experience, m.skill_ids,
			a.mentor_approval_status = 'APPROVED' AND a.locked_at IS NULL AS available
			""";

	private static final String FROM = """
			FROM identity.account a JOIN identity.mentor_application m ON m.account_id = a.id
			WHERE a.role = 'MENTOR' AND a.erased_at IS NULL AND m.submitted_at IS NOT NULL
			""";

	private record Row(UUID id, @Nullable String login, Instant createdAt, String displayName, String experience,
			int years, List<UUID> skillIds, boolean available) {
	}

	private final JdbcClient jdbc;

	private final Catalog catalog;

	JdbcMentorDirectory(JdbcClient jdbc, Catalog catalog) {
		this.jdbc = jdbc;
		this.catalog = catalog;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<MentorCard> search(String query, Collection<UUID> skillIds, Pageable pageable) {
		Optional<KeywordQuery> keyword = KeywordQuery.of(query, "m.search_vector");
		Map<String, Object> params = new HashMap<>();
		StringBuilder where = new StringBuilder(FROM)
			.append(" AND a.mentor_approval_status = 'APPROVED' AND a.locked_at IS NULL");
		keyword.ifPresent(found -> {
			where.append(" AND ").append(found.condition());
			params.putAll(found.params());
		});
		if (!skillIds.isEmpty()) {
			where.append(" AND m.skill_ids && CAST(:skills AS UUID[])");
			params.put("skills", JdbcStudentDirectory.uuidArray(skillIds));
		}
		// FR-14: a keyword orders by relevance alone, then the account ID; the Skill filter alone by name.
		String order = keyword.map(found -> found.rank("m.search_vector") + " DESC, a.id")
			.orElse("m.display_name, a.id");
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
		Optional<Row> found = this.jdbc.sql("SELECT " + COLUMNS + FROM
				+ " AND a.mentor_approval_status IN ('APPROVED', 'REVOKED') AND lower(a.github_login) = lower(:login)")
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

	private Map<UUID, String> names(List<Row> rows) {
		Map<UUID, String> names = new HashMap<>();
		for (Skill skill : this.catalog.byIds(rows.stream().flatMap(row -> row.skillIds().stream()).distinct().toList())) {
			names.put(skill.id(), skill.name());
		}
		return names;
	}

	private static MentorCard card(Row row, Map<UUID, String> names) {
		return new MentorCard(row.id(), row.login(), row.displayName(), row.years(),
				row.skillIds().stream().map(names::get).filter(name -> name != null).toList(), row.available());
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("github_login"),
				rs.getTimestamp("created_at").toInstant(), rs.getString("display_name"), rs.getString("experience"),
				rs.getInt("years_of_experience"), JdbcStudentDirectory.uuids(rs.getArray("skill_ids")),
				rs.getBoolean("available"));
	}

}
