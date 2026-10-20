package vn.vibefix.catalog.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import vn.vibefix.catalog.CatalogAdministration;
import vn.vibefix.catalog.SkillRenamed;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link CatalogAdministration} over the {@code catalog} schema. Names are unique through the schema's
 * {@code platform.vi_normalize} indexes, so a clash surfaces as the right refusal.
 */
@Component
class CatalogChanges implements CatalogAdministration {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern TOOL = Pattern.compile("^[A-Z0-9_]{1,30}$");

	private final JdbcClient jdbc;

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	private final Clock clock;

	CatalogChanges(JdbcClient jdbc, ApplicationEventPublisher events, IdGenerator ids, Clock clock) {
		this.jdbc = jdbc;
		this.events = events;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<CategoryRow> categories() {
		return this.jdbc.sql("""
				SELECT c.id, c.name, c.display_order, c.active, count(s.id) FILTER (WHERE s.active) AS active_skills
				FROM catalog.skill_category c LEFT JOIN catalog.skill s ON s.category_id = c.id
				GROUP BY c.id ORDER BY c.display_order, c.name
				""").query((rs, n) -> new CategoryRow(rs.getObject("id", UUID.class), rs.getString("name"),
				rs.getInt("display_order"), rs.getBoolean("active"), rs.getInt("active_skills"))).list();
	}

	@Override
	@Transactional(readOnly = true)
	public SkillPage skills(@Nullable String query, @Nullable UUID categoryId, @Nullable Boolean active, int page) {
		StringBuilder where = new StringBuilder(" WHERE TRUE");
		Map<String, Object> params = new HashMap<>();
		if (query != null && !query.isBlank()) {
			where.append(" AND platform.vi_normalize(s.name) LIKE '%' || platform.vi_normalize(:q) || '%' ESCAPE '\\'");
			params.put("q", query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
		}
		if (categoryId != null) {
			where.append(" AND s.category_id = :category");
			params.put("category", categoryId);
		}
		if (active != null) {
			where.append(" AND s.active = :active");
			params.put("active", active);
		}
		String from = " FROM catalog.skill s JOIN catalog.skill_category c ON c.id = s.category_id";
		long total = this.jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
		int current = Math.max(0, page);
		params.put("size", PAGE_SIZE);
		params.put("offset", current * PAGE_SIZE);
		List<SkillRow> rows = this.jdbc.sql("""
				SELECT s.id, s.category_id, c.name AS category_name, s.name, s.hint, s.match_languages, s.match_tools, s.active
				""" + from + where + " ORDER BY c.display_order, s.display_order, s.name LIMIT :size OFFSET :offset")
			.params(params)
			.query((rs, n) -> new SkillRow(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class),
					rs.getString("category_name"), rs.getString("name"), rs.getString("hint"), strings(rs.getArray("match_languages")),
					strings(rs.getArray("match_tools")), rs.getBoolean("active")))
			.list();
		return new SkillPage(rows, total, current, PAGE_SIZE);
	}

	@Override
	@Transactional
	public Saved createSkill(UUID administrator, SkillInput input) {
		SkillInput clean = validate(input);
		requireCategory(clean.categoryId());
		UUID id = this.ids.newId();
		Instant now = now();
		try {
			this.jdbc.sql("""
					INSERT INTO catalog.skill (id, category_id, name, hint, match_languages, match_tools, display_order, active,
					    created_at, updated_at)
					VALUES (:id, :category, :name, :hint, :languages, :tools,
					    (SELECT coalesce(max(display_order), 0) + 10 FROM catalog.skill WHERE category_id = :category), TRUE, :now, :now)
					""")
				.param("id", id)
				.param("category", clean.categoryId())
				.param("name", clean.name())
				.param("hint", clean.hint())
				.param("languages", clean.matchLanguages().toArray(String[]::new))
				.param("tools", clean.matchTools().toArray(String[]::new))
				.param("now", Timestamp.from(now))
				.update();
		}
		catch (DuplicateKeyException taken) {
			throw new ApiException(ErrorCode.CONFLICT, CatalogReason.SKILL_NAME_TAKEN, Map.of("field", "name"));
		}
		return new Saved(id, change(administrator, "SKILL_CREATED", id, null, skillValues(clean, true), now));
	}

	@Override
	@Transactional
	public Saved updateSkill(UUID administrator, UUID skillId, SkillInput input) {
		SkillInput clean = validate(input);
		SkillRow old = lockSkill(skillId);
		requireCategory(clean.categoryId());
		Instant now = now();
		try {
			this.jdbc.sql("""
					UPDATE catalog.skill SET category_id = :category, name = :name, hint = :hint, match_languages = :languages,
					    match_tools = :tools, updated_at = :now
					WHERE id = :id
					""")
				.param("id", skillId)
				.param("category", clean.categoryId())
				.param("name", clean.name())
				.param("hint", clean.hint())
				.param("languages", clean.matchLanguages().toArray(String[]::new))
				.param("tools", clean.matchTools().toArray(String[]::new))
				.param("now", Timestamp.from(now))
				.update();
		}
		catch (DuplicateKeyException taken) {
			throw new ApiException(ErrorCode.CONFLICT, CatalogReason.SKILL_NAME_TAKEN, Map.of("field", "name"));
		}
		if (!old.name().equals(clean.name())) {
			this.events.publishEvent(new SkillRenamed(this.ids.newId(), skillId, clean.name()));
		}
		SkillInput before = new SkillInput(old.categoryId(), old.name(), old.hint(), old.matchLanguages(), old.matchTools());
		return new Saved(skillId, change(administrator, "SKILL_UPDATED", skillId, skillValues(before, old.active()),
				skillValues(clean, old.active()), now));
	}

	@Override
	@Transactional
	public Saved setSkillActive(UUID administrator, UUID skillId, boolean active) {
		SkillRow old = lockSkill(skillId);
		Instant now = now();
		this.jdbc.sql("UPDATE catalog.skill SET active = :active, updated_at = :now WHERE id = :id")
			.param("id", skillId)
			.param("active", active)
			.param("now", Timestamp.from(now))
			.update();
		return new Saved(skillId, change(administrator, "SKILL_ACTIVE", skillId, Map.of("active", old.active()),
				Map.of("active", active), now));
	}

	@Override
	@Transactional
	public Saved createCategory(UUID administrator, String name) {
		String clean = categoryName(name);
		UUID id = this.ids.newId();
		Instant now = now();
		try {
			this.jdbc.sql("""
					INSERT INTO catalog.skill_category (id, name, display_order, active, created_at, updated_at)
					VALUES (:id, :name, (SELECT coalesce(max(display_order), 0) + 10 FROM catalog.skill_category), TRUE, :now, :now)
					""").param("id", id).param("name", clean).param("now", Timestamp.from(now)).update();
		}
		catch (DuplicateKeyException taken) {
			throw new ApiException(ErrorCode.CONFLICT, CatalogReason.CATEGORY_NAME_TAKEN, Map.of("field", "name"));
		}
		return new Saved(id, change(administrator, "CATEGORY_CREATED", id, null, Map.of("name", clean), now));
	}

	@Override
	@Transactional
	public Saved renameCategory(UUID administrator, UUID categoryId, String name) {
		String clean = categoryName(name);
		CategoryRow old = lockCategory(categoryId);
		Instant now = now();
		try {
			this.jdbc.sql("UPDATE catalog.skill_category SET name = :name, updated_at = :now WHERE id = :id")
				.param("id", categoryId)
				.param("name", clean)
				.param("now", Timestamp.from(now))
				.update();
		}
		catch (DuplicateKeyException taken) {
			throw new ApiException(ErrorCode.CONFLICT, CatalogReason.CATEGORY_NAME_TAKEN, Map.of("field", "name"));
		}
		return new Saved(categoryId, change(administrator, "CATEGORY_RENAMED", categoryId, Map.of("name", old.name()),
				Map.of("name", clean), now));
	}

	@Override
	@Transactional
	public Saved setCategoryActive(UUID administrator, UUID categoryId, boolean active) {
		CategoryRow old = lockCategory(categoryId);
		if (!active && old.activeSkills() > 0) {
			throw new ApiException(ErrorCode.CONFLICT, CatalogReason.CATEGORY_HAS_ACTIVE_SKILLS);
		}
		Instant now = now();
		this.jdbc.sql("UPDATE catalog.skill_category SET active = :active, updated_at = :now WHERE id = :id")
			.param("id", categoryId)
			.param("active", active)
			.param("now", Timestamp.from(now))
			.update();
		return new Saved(categoryId, change(administrator, "CATEGORY_ACTIVE", categoryId, Map.of("active", old.active()),
				Map.of("active", active), now));
	}

	@Override
	@Transactional
	public Saved reorderCategories(UUID administrator, List<UUID> order) {
		List<CategoryRow> current = this.jdbc.sql("SELECT id FROM catalog.skill_category FOR UPDATE")
			.query(UUID.class)
			.list()
			.stream()
			.map(id -> new CategoryRow(id, "", 0, true, 0))
			.toList();
		List<UUID> before = categories().stream().map(CategoryRow::id).toList();
		if (order.size() != current.size() || !new HashSet<>(order).equals(new HashSet<>(before))) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		Instant now = now();
		for (int index = 0; index < order.size(); index++) {
			this.jdbc.sql("UPDATE catalog.skill_category SET display_order = :order, updated_at = :now WHERE id = :id")
				.param("id", order.get(index))
				.param("order", (index + 1) * 10)
				.param("now", Timestamp.from(now))
				.update();
		}
		UUID change = change(administrator, "CATEGORIES_REORDERED", null, Map.of("order", before.stream().map(UUID::toString).toList()),
				Map.of("order", order.stream().map(UUID::toString).toList()), now);
		return new Saved(change, change);
	}

	private SkillInput validate(SkillInput input) {
		String name = input.name() == null ? "" : input.name().strip();
		String hint = input.hint() == null ? "" : input.hint().strip();
		if (input.categoryId() == null || name.isEmpty() || name.length() > 60 || hint.isEmpty() || hint.length() > 160) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		List<String> languages = new ArrayList<>();
		for (String language : input.matchLanguages() == null ? List.<String>of() : input.matchLanguages()) {
			String clean = language == null ? "" : language.strip();
			if (clean.isEmpty() || clean.length() > 40) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			if (!languages.contains(clean)) {
				languages.add(clean);
			}
		}
		List<String> tools = new ArrayList<>();
		for (String tool : input.matchTools() == null ? List.<String>of() : input.matchTools()) {
			String clean = tool == null ? "" : tool.strip();
			if (!TOOL.matcher(clean).matches()) {
				throw new ApiException(ErrorCode.VALIDATION);
			}
			if (!tools.contains(clean)) {
				tools.add(clean);
			}
		}
		if (languages.size() > 20 || tools.size() > 20) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		return new SkillInput(input.categoryId(), name, hint, List.copyOf(languages), List.copyOf(tools));
	}

	private static String categoryName(String name) {
		String clean = name == null ? "" : name.strip();
		if (clean.isEmpty() || clean.length() > 60) {
			throw new ApiException(ErrorCode.VALIDATION);
		}
		return clean;
	}

	private void requireCategory(UUID categoryId) {
		boolean exists = this.jdbc.sql("SELECT count(*) FROM catalog.skill_category WHERE id = :id")
			.param("id", categoryId)
			.query(Long.class)
			.single() > 0;
		if (!exists) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	private SkillRow lockSkill(UUID skillId) {
		return this.jdbc.sql("""
				SELECT s.id, s.category_id, '' AS category_name, s.name, s.hint, s.match_languages, s.match_tools, s.active
				FROM catalog.skill s WHERE s.id = :id FOR UPDATE
				""").param("id", skillId).query((rs, n) -> new SkillRow(rs.getObject("id", UUID.class),
				rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getString("name"),
				rs.getString("hint"), strings(rs.getArray("match_languages")), strings(rs.getArray("match_tools")),
				rs.getBoolean("active"))).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private CategoryRow lockCategory(UUID categoryId) {
		this.jdbc.sql("SELECT id FROM catalog.skill_category WHERE id = :id FOR UPDATE")
			.param("id", categoryId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return categories().stream().filter(row -> row.id().equals(categoryId)).findFirst().orElseThrow();
	}

	private UUID change(UUID administrator, String kind, @Nullable UUID subject, @Nullable Map<String, ?> old,
			Map<String, ?> value, Instant now) {
		UUID id = this.ids.newId();
		this.jdbc.sql("""
				INSERT INTO catalog.change (id, kind, subject_id, old_value, new_value, changed_by, changed_at)
				VALUES (:id, :kind, :subject, CAST(:old AS JSONB), CAST(:value AS JSONB), :by, :now)
				""")
			.param("id", id)
			.param("kind", kind)
			.param("subject", subject)
			.param("old", old == null ? null : JSON.writeValueAsString(old))
			.param("value", JSON.writeValueAsString(value))
			.param("by", administrator)
			.param("now", Timestamp.from(now))
			.update();
		return id;
	}

	private static Map<String, Object> skillValues(SkillInput input, boolean active) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("categoryId", input.categoryId().toString());
		values.put("name", input.name());
		values.put("hint", input.hint());
		values.put("matchLanguages", input.matchLanguages());
		values.put("matchTools", input.matchTools());
		values.put("active", active);
		return values;
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static List<String> strings(@Nullable Array array) throws SQLException {
		return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
	}

}
