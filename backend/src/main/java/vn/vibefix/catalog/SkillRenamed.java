package vn.vibefix.catalog;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Skill's name changed (AD-38): every holder rewrites the {@code skill_names} it keeps for search
 * on the rows that hold the Skill, in a replay-safe listener (AD-9).
 */
public record SkillRenamed(UUID eventId, UUID skillId, String name) implements DomainEvent {
}
