package vn.vibefix.catalog;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.ApiException;

/**
 * ADM-12 (FR-72, AD-38): the Skill Categories and Skills as Administrators manage them. Every write
 * records a change with its old and new values and returns its ID for the Audit Log; a rename publishes
 * {@link SkillRenamed}. Nothing is deleted: "Ngừng dùng" takes a Skill out of every picker, filter, and
 * suggestion while records keep it.
 */
public interface CatalogAdministration {

	/** A page holds this many Skills. */
	int PAGE_SIZE = 25;

	record CategoryRow(UUID id, String name, int displayOrder, boolean active, int activeSkills) {
	}

	record SkillRow(UUID id, UUID categoryId, String categoryName, String name, String hint,
			List<String> matchLanguages, List<String> matchTools, boolean active) {
	}

	record SkillPage(List<SkillRow> items, long total, int page, int size) {
	}

	/** @param matchTools AI tool keys such as {@code LOVABLE}; @param matchLanguages GitHub language names */
	record SkillInput(UUID categoryId, String name, String hint, List<String> matchLanguages, List<String> matchTools) {
	}

	/** A write's result: the subject and the recorded change. */
	record Saved(UUID id, UUID changeId) {
	}

	List<CategoryRow> categories();

	/** Skills by Category and status, the name matched ignoring accents and case. */
	SkillPage skills(@Nullable String query, @Nullable UUID categoryId, @Nullable Boolean active, int page);

	/** @throws ApiException {@code VALIDATION}; {@code CONFLICT SKILL_NAME_TAKEN}; {@code NOT_FOUND} for the Category */
	Saved createSkill(UUID administrator, SkillInput input);

	/** @throws ApiException as {@link #createSkill}, and {@code NOT_FOUND} for the Skill */
	Saved updateSkill(UUID administrator, UUID skillId, SkillInput input);

	/** "Ngừng dùng" or "Dùng lại". @throws ApiException {@code NOT_FOUND} */
	Saved setSkillActive(UUID administrator, UUID skillId, boolean active);

	/** @throws ApiException {@code VALIDATION}; {@code CONFLICT CATEGORY_NAME_TAKEN} */
	Saved createCategory(UUID administrator, String name);

	/** @throws ApiException as {@link #createCategory}, and {@code NOT_FOUND} */
	Saved renameCategory(UUID administrator, UUID categoryId, String name);

	/** @throws ApiException {@code NOT_FOUND}; {@code CONFLICT CATEGORY_HAS_ACTIVE_SKILLS} when deactivating */
	Saved setCategoryActive(UUID administrator, UUID categoryId, boolean active);

	/**
	 * Puts every Category in the given order; the picker's groups follow it.
	 * @throws ApiException {@code VALIDATION} unless {@code order} names each Category once
	 */
	Saved reorderCategories(UUID administrator, List<UUID> order);

}
