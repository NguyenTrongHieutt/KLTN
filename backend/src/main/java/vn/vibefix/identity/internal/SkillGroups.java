package vn.vibefix.identity.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;
import vn.vibefix.catalog.SkillCategory;
import vn.vibefix.identity.SkillGroup;

/** A person's Skills by Skill Category, for public profiles (UX-DR65). */
final class SkillGroups {

	private SkillGroups() {
	}

	/** By Skill Category in the catalog's order; Skills of a retired Category come last, unnamed. */
	static List<SkillGroup> of(Catalog catalog, List<Skill> skills) {
		Map<UUID, String> categories = new LinkedHashMap<>();
		for (SkillCategory category : catalog.active()) {
			categories.put(category.id(), category.name());
		}
		Map<UUID, List<String>> byCategory = new LinkedHashMap<>();
		for (Skill skill : skills) {
			byCategory.computeIfAbsent(skill.categoryId(), id -> new ArrayList<>()).add(skill.name());
		}
		List<SkillGroup> groups = new ArrayList<>();
		for (Map.Entry<UUID, String> category : categories.entrySet()) {
			List<String> names = byCategory.remove(category.getKey());
			if (names != null) {
				groups.add(new SkillGroup(category.getValue(), names));
			}
		}
		List<String> rest = byCategory.values().stream().flatMap(List::stream).toList();
		if (!rest.isEmpty()) {
			groups.add(new SkillGroup(null, rest));
		}
		return groups;
	}

}
