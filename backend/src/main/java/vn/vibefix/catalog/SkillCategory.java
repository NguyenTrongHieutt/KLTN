package vn.vibefix.catalog;

import java.util.List;
import java.util.UUID;

/** A Skill Category with its Skills, both in the Administrator's display order (FR-72). */
public record SkillCategory(UUID id, String name, List<Skill> skills) {

	public SkillCategory {
		skills = List.copyOf(skills);
	}

}
