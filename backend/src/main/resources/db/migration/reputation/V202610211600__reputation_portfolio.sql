-- Portfolio Entries (FR-50, AD-33, AD-38): J9 creates one for the Mentor and one for each Crew member
-- who was ever active when the Job has Accepted work, with facts frozen from records; the Mentor drafts
-- one summary per Job and the Customer approves, edits, or declines it before the Rating Window ends.
-- An entry is published once its person confirmed the data deletion (FR-66).
-- Personal data: exported for its person; deleted on their erasure (the summary keeps no author once
-- erased); a Product's deletion clears the Product name and the Job title it holds.

CREATE TABLE reputation.portfolio_entry
(
    id                     UUID PRIMARY KEY,
    job_id                 UUID        NOT NULL,
    product_id             UUID        NOT NULL,
    account_id             UUID        NOT NULL,
    role                   TEXT        NOT NULL CHECK (role IN ('MENTOR', 'CREW')),
    job_title              TEXT        NOT NULL,
    product_name           TEXT        NOT NULL,
    job_types              TEXT[]      NOT NULL,
    skill_ids              UUID[]      NOT NULL,
    milestones_accepted    INTEGER     NOT NULL CHECK (milestones_accepted >= 1),
    work_items_approved    INTEGER CHECK (work_items_approved >= 0),
    milestones_handed_over INTEGER CHECK (milestones_handed_over >= 0),
    reviews_written        INTEGER CHECK (reviews_written >= 0),
    closed_at              TIMESTAMPTZ NOT NULL,
    published_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, account_id),
    CHECK ((role = 'CREW') = (work_items_approved IS NOT NULL)),
    CHECK ((role = 'MENTOR') = (milestones_handed_over IS NOT NULL AND reviews_written IS NOT NULL))
);

CREATE INDEX portfolio_entry_account_idx ON reputation.portfolio_entry (account_id, closed_at DESC);

CREATE INDEX portfolio_entry_product_idx ON reputation.portfolio_entry (product_id);

GRANT SELECT, INSERT, DELETE ON reputation.portfolio_entry TO ${runtime_role};

GRANT UPDATE (published_at, product_name, job_title) ON reputation.portfolio_entry TO ${runtime_role};

CREATE TABLE reputation.portfolio_summary
(
    job_id             UUID PRIMARY KEY,
    product_id         UUID        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('DRAFT_DUE', 'AWAITING_APPROVAL', 'APPROVED', 'DECLINED', 'LAPSED')),
    text               TEXT CHECK (char_length(text) BETWEEN 1 AND 600),
    show_product_name  BOOLEAN     NOT NULL DEFAULT false,
    drafted_by         UUID,
    drafted_at         TIMESTAMPTZ,
    decided_by         UUID,
    decided_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    CHECK (status NOT IN ('AWAITING_APPROVAL', 'APPROVED') OR text IS NOT NULL)
);

GRANT SELECT, INSERT, DELETE ON reputation.portfolio_summary TO ${runtime_role};

GRANT UPDATE (status, text, show_product_name, drafted_by, drafted_at, decided_by, decided_at)
    ON reputation.portfolio_summary TO ${runtime_role};
