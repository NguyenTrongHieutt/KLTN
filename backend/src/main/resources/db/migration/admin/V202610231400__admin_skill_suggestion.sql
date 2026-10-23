-- FR-73, AD-34, AD-38 (Story 16.4): a Customer's, a Mentor's, or a Student's suggestion of a Skill missing from
-- the catalog, at most 5 a day each, and how an Administrator decided it on ADM-12: approved into a Skill the
-- catalog command created, or declined with the reason the suggester reads. A name matching an inactive Skill is
-- flagged with that Skill.
-- Personal data: the suggester's own suggestions are exported; on erasure the suggester and the deciding
-- Administrator are cleared.

CREATE TABLE admin.skill_suggestion
(
    id                   UUID PRIMARY KEY,
    suggester_account_id UUID,
    suggester_role       TEXT        NOT NULL CHECK (suggester_role IN ('CUSTOMER', 'MENTOR', 'STUDENT')),
    name                 TEXT        NOT NULL CHECK (char_length(name) BETWEEN 2 AND 60),
    category_id          UUID,
    note                 TEXT CHECK (char_length(note) BETWEEN 1 AND 500),
    inactive_skill_id    UUID,
    status               TEXT        NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'DECLINED')),
    created_at           TIMESTAMPTZ NOT NULL,
    decided_by_admin_id  UUID,
    decided_at           TIMESTAMPTZ,
    decline_reason       TEXT CHECK (char_length(decline_reason) BETWEEN 1 AND 500),
    skill_id             UUID,
    CHECK ((status = 'OPEN') = (decided_at IS NULL)),
    CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL)),
    CHECK ((status = 'APPROVED') = (skill_id IS NOT NULL))
);

CREATE INDEX skill_suggestion_suggester_idx ON admin.skill_suggestion (suggester_account_id, created_at);

CREATE INDEX skill_suggestion_queue_idx ON admin.skill_suggestion (status, created_at);

GRANT SELECT, INSERT ON admin.skill_suggestion TO ${runtime_role};

GRANT UPDATE (suggester_account_id, status, decided_by_admin_id, decided_at, decline_reason, skill_id)
    ON admin.skill_suggestion TO ${runtime_role};
