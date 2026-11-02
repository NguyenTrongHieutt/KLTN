-- FR-42, AD-15, AD-24, AD-29, AD-41 (Story 22.1): each release (M5, or M8 with a release above 0) creates its
-- Milestone's one split, with its parties fixed then and never recomputed. A split with the Mentor alone is final at
-- once, with one System-authored MENTOR_ONLY version at 100%; any other waits in AWAITING_DRAFT for Story 22.2.
-- Versions and shares are insert-only. No foreign key reaches job.job, job.milestone or job.crew_row, which can go
-- before these rows. A write here changes neither Job.version nor crewVersion.
-- A Product deleted while a split of its Job is not final leaves a bare job.job row (product_deleted_at set, the
-- Customer's words cleared, the title kept) that only the split ports read; every other read treats it as gone.
-- Personal data: financial records under NFR-10's legal retention, never deleted; an erasure sets the person's
-- party_account_id, mentor_account_id and author_account_id to NULL (JobPersonalData).

ALTER TABLE job.job
    ADD COLUMN product_deleted_at TIMESTAMPTZ;

CREATE TABLE job.milestone_split
(
    id                UUID PRIMARY KEY,
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    milestone_id      UUID        NOT NULL UNIQUE,
    milestone_number  INTEGER     NOT NULL CHECK (milestone_number > 0),
    mentor_account_id UUID,
    status            TEXT        NOT NULL CHECK (status IN ('AWAITING_DRAFT', 'PROPOSED', 'IN_RESOLUTION', 'FINAL')),
    cap_applies       BOOLEAN     NOT NULL,
    released_at       TIMESTAMPTZ NOT NULL,
    draft_due_at      TIMESTAMPTZ NOT NULL,
    final_version_no  INTEGER CHECK (final_version_no > 0),
    created_at        TIMESTAMPTZ NOT NULL,
    CHECK ((status = 'FINAL') = (final_version_no IS NOT NULL))
);

CREATE INDEX milestone_split_job_idx ON job.milestone_split (job_id);

CREATE INDEX milestone_split_product_idx ON job.milestone_split (product_id);

CREATE INDEX milestone_split_mentor_idx ON job.milestone_split (mentor_account_id);

GRANT SELECT, INSERT ON job.milestone_split TO ${runtime_role};

GRANT UPDATE (status, final_version_no, mentor_account_id) ON job.milestone_split TO ${runtime_role};

CREATE TABLE job.milestone_split_party
(
    id                UUID PRIMARY KEY,
    split_id          UUID    NOT NULL REFERENCES job.milestone_split (id),
    party_account_id  UUID,
    is_mentor         BOOLEAN NOT NULL,
    whole_interval    BOOLEAN NOT NULL,
    active_at_release BOOLEAN NOT NULL,
    position          INTEGER NOT NULL CHECK (position >= 0),
    UNIQUE (split_id, id),
    UNIQUE (split_id, position),
    UNIQUE (split_id, party_account_id),
    CHECK (is_mentor = (position = 0))
);

CREATE INDEX milestone_split_party_account_idx ON job.milestone_split_party (party_account_id);

GRANT SELECT, INSERT ON job.milestone_split_party TO ${runtime_role};

GRANT UPDATE (party_account_id) ON job.milestone_split_party TO ${runtime_role};

CREATE TABLE job.milestone_split_version
(
    split_id          UUID        NOT NULL REFERENCES job.milestone_split (id),
    version_no        INTEGER     NOT NULL CHECK (version_no > 0),
    kind              TEXT        NOT NULL CHECK (kind IN ('DRAFT', 'DEFAULT', 'DECISION', 'MENTOR_ONLY')),
    cap_percent       INTEGER CHECK (cap_percent BETWEEN 0 AND 100),
    author_account_id UUID,
    created_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (split_id, version_no)
);

CREATE INDEX milestone_split_version_author_idx ON job.milestone_split_version (author_account_id);

GRANT SELECT, INSERT ON job.milestone_split_version TO ${runtime_role};

GRANT UPDATE (author_account_id) ON job.milestone_split_version TO ${runtime_role};

CREATE TABLE job.milestone_split_share
(
    split_id   UUID    NOT NULL,
    version_no INTEGER NOT NULL,
    party_id   UUID    NOT NULL,
    percent    INTEGER NOT NULL CHECK (percent BETWEEN 0 AND 100),
    PRIMARY KEY (split_id, version_no, party_id),
    FOREIGN KEY (split_id, version_no) REFERENCES job.milestone_split_version (split_id, version_no),
    FOREIGN KEY (split_id, party_id) REFERENCES job.milestone_split_party (split_id, id)
);

GRANT SELECT, INSERT ON job.milestone_split_share TO ${runtime_role};
