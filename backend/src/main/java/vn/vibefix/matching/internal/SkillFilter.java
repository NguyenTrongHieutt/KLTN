package vn.vibefix.matching.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.catalog.SkillCategory;

/**
 * The Skill filter of CUS-8 and MEN-2 (AD-38): the active chosen Skills and every active Skill of the
 * chosen Categories. It narrows search results and Recommendations alike and never reorders them.
 */
final class SkillFilter {

	private SkillFilter() {
	}

	/**
	 * The Skills to match any of, or {@code null} when no filter was chosen; an empty set (only
	 * inactive Skills chosen) matches nothing.
	 */
	static @Nullable Set<UUID> resolve(Catalog catalog, @Nullable List<UUID> skills, @Nullable List<UUID> categories) {
		List<UUID> chosenSkills = skills == null ? List.of() : skills;
		List<UUID> chosenCategories = categories == null ? List.of() : categories;
		if (chosenSkills.isEmpty() && chosenCategories.isEmpty()) {
			return null;
		}
		Set<UUID> ids = new LinkedHashSet<>();
		catalog.byIds(chosenSkills).stream().filter(Skill::active).forEach(skill -> ids.add(skill.id()));
		if (!chosenCategories.isEmpty()) {
			for (SkillCategory category : catalog.active()) {
				if (chosenCategories.contains(category.id())) {
					category.skills().forEach(skill -> ids.add(skill.id()));
				}
			}
		}
		return ids;
	}

	/** Whether Skills held pass the filter: always without one, else when any is among it. */
	static boolean keeps(@Nullable Set<UUID> filter, List<UUID> held) {
		return filter == null || held.stream().anyMatch(filter::contains);
	}

}
