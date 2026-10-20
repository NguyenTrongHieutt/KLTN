package vn.vibefix.catalog.internal;

/**
 * The {@code reason} of a {@code catalog} refusal (AD-23); the SPA reads {@code reason.catalog.{REASON}}
 * in {@code vi.json} (EXPERIENCE.md, Messages from Error Codes).
 */
enum CatalogReason {

	/** A newly added Skill is unknown or no longer active. */
	SKILL_INACTIVE,

	/** More Skills than the holder may keep; the answer's {@code limit} says how many. */
	SKILL_LIMIT,

	/** FR-72: another Skill has this name, ignoring accents and case. */
	SKILL_NAME_TAKEN,

	/** FR-72: another Skill Category has this name. */
	CATEGORY_NAME_TAKEN,

	/** FR-72: a Category with active Skills cannot be deactivated. */
	CATEGORY_HAS_ACTIVE_SKILLS

}
