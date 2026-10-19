package vn.vibefix.identity;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One Skill Category with a person's Skills in it, in the catalog's order (UX-DR65), on Mentor and
 * Student profiles; a retired Category has no name.
 */
public record SkillGroup(@Nullable String category, List<String> skills) {

	public SkillGroup {
		skills = List.copyOf(skills);
	}

}
