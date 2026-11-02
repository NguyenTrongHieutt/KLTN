-- FR-61, FR-83, AD-24, AD-29 (Story 22.2): a split is drafted, confirmed, edited and defaulted. Each save increments
-- confirmation_round and sets confirmation_due_at, the one due time a reader takes (Deadlines.dueAt reads the earliest
-- pending row, and old rows stay until they end OBSOLETE). A final split records when and how it became final. The
-- MENTOR_ONLY rows Story 22.1 made are the only final ones so far, final when created.
-- job.milestone_split_confirmation is append-only, and a confirmation stays on its own version: a later save clears
-- nothing by deleting. The Mentor never has a row. It holds no account column, as its party row names the person.
-- Personal data: financial records under NFR-10's legal retention, never deleted; an erasure anonymizes the party row
-- (JobPersonalData), and these rows then name nobody.

ALTER TABLE job.milestone_split
    ADD COLUMN confirmation_round  INTEGER NOT NULL DEFAULT 0 CHECK (confirmation_round >= 0),
    ADD COLUMN confirmation_due_at TIMESTAMPTZ,
    ADD COLUMN final_at            TIMESTAMPTZ,
    ADD COLUMN finality            TEXT CHECK (finality IN ('CONFIRMED', 'DEADLINE', 'DECISION', 'MENTOR_ONLY'));

UPDATE job.milestone_split
SET final_at = created_at,
    finality = 'MENTOR_ONLY'
WHERE status = 'FINAL';

ALTER TABLE job.milestone_split
    ADD CONSTRAINT milestone_split_final_when_how CHECK ((status = 'FINAL') = (final_at IS NOT NULL)
        AND (status = 'FINAL') = (finality IS NOT NULL)),
    ADD CONSTRAINT milestone_split_proposed_due CHECK (status <> 'PROPOSED' OR confirmation_due_at IS NOT NULL);

GRANT UPDATE (confirmation_round, confirmation_due_at, final_at, finality) ON job.milestone_split TO ${runtime_role};

CREATE TABLE job.milestone_split_confirmation
(
    split_id     UUID        NOT NULL,
    version_no   INTEGER     NOT NULL,
    party_id     UUID        NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (split_id, version_no, party_id),
    FOREIGN KEY (split_id, version_no) REFERENCES job.milestone_split_version (split_id, version_no),
    FOREIGN KEY (split_id, party_id) REFERENCES job.milestone_split_party (split_id, id)
);

GRANT SELECT, INSERT ON job.milestone_split_confirmation TO ${runtime_role};
