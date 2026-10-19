package vn.vibefix.catalog;

import java.util.UUID;

/**
 * A Skill of the catalog (FR-72).
 * @param hint the one-line plain explanation Customer screens show (NFR-12)
 * @param active an inactive Skill stays on the records that hold it, but cannot be added again
 */
public record Skill(UUID id, UUID categoryId, String name, String hint, boolean active) {
}
