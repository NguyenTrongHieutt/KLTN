package vn.vibefix.catalog.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.catalog.SkillCategory;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/**
 * {@link Catalog} on the {@code catalog} schema. The catalog is small (about 60 Skills), so every
 * call reads it; the API's ETag lets browsers skip the download.
 */
@Component
class JdbcCatalog implements Catalog {

	private final JdbcClient jdbc;

	JdbcCatalog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<SkillCategory> active() {
		record CategoryRow(UUID id, String name) {
		}
		List<CategoryRow> categories = this.jdbc.sql("""
				SELECT id, name FROM catalog.skill_category WHERE active ORDER BY display_order, id
				""").query((rs, n) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("name"))).list();
		Map<UUID, List<Skill>> skills = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT id, category_id, name, hint, active FROM catalog.skill WHERE active ORDER BY display_order, id
				""")
			.query((rs, n) -> skill(rs))
			.list()
			.forEach(skill -> skills.computeIfAbsent(skill.categoryId(), id -> new ArrayList<>()).add(skill));
		return categories.stream()
			.map(category -> new SkillCategory(category.id(), category.name(),
					skills.getOrDefault(category.id(), List.of())))
			.toList();
	}

	@Override
	public List<Skill> byIds(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, Skill> found = new LinkedHashMap<>();
		this.jdbc.sql("SELECT id, category_id, name, hint, active FROM catalog.skill WHERE id IN (:ids)")
			.param("ids", List.copyOf(new LinkedHashSet<>(ids)))
			.query((rs, n) -> skill(rs))
			.list()
			.forEach(skill -> found.put(skill.id(), skill));
		return new LinkedHashSet<>(ids).stream().map(found::get).filter(Objects::nonNull).toList();
	}

	@Override
	public void requireActive(Collection<UUID> added) {
		long active = byIds(added).stream().filter(Skill::active).count();
		if (active != new LinkedHashSet<>(added).size()) {
			throw new ApiException(ErrorCode.VALIDATION, CatalogReason.SKILL_INACTIVE);
		}
	}

	@Override
	public void requireAtMost(int count, int max) {
		if (count > max) {
			throw new ApiException(ErrorCode.VALIDATION, CatalogReason.SKILL_LIMIT, Map.of("limit", max));
		}
	}

	private static Skill skill(ResultSet rs) throws SQLException {
		return new Skill(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getString("name"),
				rs.getString("hint"), rs.getBoolean("active"));
	}

}
