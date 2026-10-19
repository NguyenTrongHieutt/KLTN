package vn.vibefix.identity.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import vn.vibefix.identity.StudentDirectory;
import vn.vibefix.shared.KeywordQuery;

/**
 * Students' public fields from their profiles (FR-51, AD-36). Search and the username lookup find
 * verified, unlocked, unerased Students only; {@link #cards} describes any Student by ID, for the
 * Team screens. A hidden name hides the school and year too.
 */
@Component
class JdbcStudentDirectory implements StudentDirectory {

	private static final String COLUMNS = """
			a.id, a.github_login, a.locked_at IS NULL AND a.erased_at IS NULL AS available,
			p.real_name, p.school, p.year_of_study, coalesce(p.show_name_and_school, FALSE) AS shown,
			coalesce(p.skill_ids, '{}') AS skill_ids, v.valid_until
			""";

	private static final String FROM = """
			FROM identity.account a
			LEFT JOIN identity.student_profile p ON p.account_id = a.id
			LEFT JOIN identity.student_verification v ON v.account_id = a.id
			""";

	private static final String FINDABLE = """
			a.role = 'STUDENT' AND a.locked_at IS NULL AND a.erased_at IS NULL AND v.valid_until >= :today
			""";

	/** One row before its Skill IDs are named. */
	private record Row(UUID id, @Nullable String login, boolean available, @Nullable String name,
			@Nullable String school, @Nullable Integer year, boolean shown, List<UUID> skillIds,
			@Nullable LocalDate verifiedUntil) {
	}

	private final JdbcClient jdbc;

	private final Catalog catalog;

	private final Clock clock;

	JdbcStudentDirectory(JdbcClient jdbc, Catalog catalog, Clock clock) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<StudentCard> search(String query, Collection<UUID> skillIds, Pageable pageable) {
		Optional<KeywordQuery> keyword = KeywordQuery.of(query, "p.search_vector");
		Map<String, Object> params = new HashMap<>();
		params.put("today", today());
		StringBuilder where = new StringBuilder("WHERE ").append(FINDABLE).append(" AND p.account_id IS NOT NULL");
		keyword.ifPresent(found -> {
			where.append(" AND ").append(found.condition());
			params.putAll(found.params());
		});
		if (!skillIds.isEmpty()) {
			where.append(" AND p.skill_ids && CAST(:skills AS UUID[])");
			params.put("skills", uuidArray(skillIds));
		}
		String order = keyword.map(found -> found.rank("p.search_vector") + " DESC, ").orElse("") + "a.github_login, a.id";
		List<Row> rows = this.jdbc.sql("SELECT " + COLUMNS + FROM + where + " ORDER BY " + order
				+ " LIMIT :size OFFSET :offset")
			.params(params)
			.param("size", pageable.getPageSize())
			.param("offset", pageable.getOffset())
			.query(JdbcStudentDirectory::row)
			.list();
		long total = this.jdbc.sql("SELECT count(*) " + FROM + where).params(params).query(Long.class).single();
		return new PageImpl<>(named(rows), pageable, total);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StudentCard> byLogin(String githubLogin) {
		List<Row> rows = this.jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE " + FINDABLE
				+ " AND lower(a.github_login) = lower(:login)")
			.param("today", today())
			.param("login", githubLogin.strip())
			.query(JdbcStudentDirectory::row)
			.list();
		return named(rows).stream().findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public List<StudentCard> cards(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, StudentCard> byId = named(this.jdbc.sql("SELECT " + COLUMNS + FROM
				+ "WHERE a.role = 'STUDENT' AND a.id IN (:ids)")
			.param("ids", List.copyOf(ids))
			.query(JdbcStudentDirectory::row)
			.list()).stream().collect(Collectors.toMap(StudentCard::accountId, Function.identity()));
		List<StudentCard> ordered = new ArrayList<>();
		for (UUID id : ids) {
			StudentCard card = byId.get(id);
			if (card != null) {
				ordered.add(card);
			}
		}
		return ordered;
	}

	private List<StudentCard> named(List<Row> rows) {
		Map<UUID, String> names = new LinkedHashMap<>();
		for (Skill skill : this.catalog.byIds(rows.stream().flatMap(row -> row.skillIds().stream()).distinct().toList())) {
			names.put(skill.id(), skill.name());
		}
		LocalDate today = today();
		return rows.stream()
			.map(row -> new StudentCard(row.id(), row.login(), row.shown() ? row.name() : null,
					row.shown() ? row.school() : null, row.shown() ? row.year() : null,
					row.skillIds().stream().map(names::get).filter(name -> name != null).toList(),
					row.verifiedUntil() != null && !today.isAfter(row.verifiedUntil()), row.verifiedUntil(),
					row.available()))
			.toList();
	}

	private LocalDate today() {
		return LocalDate.ofInstant(this.clock.instant(), StudentVerifications.VIETNAM);
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("github_login"), rs.getBoolean("available"),
				rs.getString("real_name"), rs.getString("school"), (Integer) rs.getObject("year_of_study", Integer.class),
				rs.getBoolean("shown"), uuids(rs.getArray("skill_ids")), rs.getObject("valid_until", LocalDate.class));
	}

	static List<UUID> uuids(@Nullable Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		Object[] values = (Object[]) array.getArray();
		List<UUID> ids = new ArrayList<>(values.length);
		for (Object value : values) {
			ids.add(value instanceof UUID id ? id : UUID.fromString(value.toString()));
		}
		return ids;
	}

	/** A PostgreSQL array literal of UUIDs, cast in the query. */
	static String uuidArray(Collection<UUID> ids) {
		return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
	}

}
