package vn.vibefix.reputation.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.catalog.Catalog;
import vn.vibefix.catalog.Skill;

/** A Portfolio Entry's frozen Skill IDs by their current names, in the frozen order (AD-38). */
@Component
class SkillNames {

	private final Catalog catalog;

	SkillNames(Catalog catalog) {
		this.catalog = catalog;
	}

	List<String> namesOf(List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> names = new HashMap<>();
		for (Skill skill : this.catalog.byIds(ids)) {
			names.put(skill.id(), skill.name());
		}
		return ids.stream().map(names::get).filter(Objects::nonNull).toList();
	}

}
