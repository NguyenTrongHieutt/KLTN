-- FR-72, FR-63, AD-38: Administrators add and edit Skills and Skill Categories on ADM-12; nothing is
-- ever deleted. Each change keeps its old and new values; the Audit Log points at it. Personal data:
-- the Administrator who saved it, cleared on erasure.

GRANT INSERT, UPDATE ON catalog.skill_category, catalog.skill TO ${runtime_role};

CREATE TABLE catalog.change
(
    id         UUID PRIMARY KEY,
    kind       TEXT        NOT NULL CHECK (kind IN ('SKILL_CREATED', 'SKILL_UPDATED', 'SKILL_ACTIVE', 'CATEGORY_CREATED',
                                                    'CATEGORY_RENAMED', 'CATEGORY_ACTIVE', 'CATEGORIES_REORDERED')),
    subject_id UUID,
    old_value  JSONB,
    new_value  JSONB       NOT NULL,
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT ON catalog.change TO ${runtime_role};

GRANT UPDATE (changed_by) ON catalog.change TO ${runtime_role};
