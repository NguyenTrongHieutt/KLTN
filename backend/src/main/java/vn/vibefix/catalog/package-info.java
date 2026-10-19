/**
 * Layer 1 (AD-3): the shared Skill catalog (FR-72, AD-38): Skill Categories and Skills, with their
 * plain hints, match keys, display order, and active flags, seeded from PRD addendum B7. Other
 * modules keep Skill IDs only and read names through {@link vn.vibefix.catalog.Catalog#byIds}; a
 * rename is published as {@link vn.vibefix.catalog.SkillRenamed}. Nothing is ever deleted. Writes
 * arrive with ADM-12, through {@code admin}.
 * <p>
 * Public API: {@link vn.vibefix.catalog.Catalog}, {@link vn.vibefix.catalog.Skill},
 * {@link vn.vibefix.catalog.SkillCategory} and {@link vn.vibefix.catalog.SkillRenamed}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.catalog;

import org.springframework.modulith.ApplicationModule;
