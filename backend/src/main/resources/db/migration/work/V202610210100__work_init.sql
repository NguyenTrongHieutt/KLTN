-- Schema work (FR-22 to FR-26, FR-76, AD-2, AD-33, AD-39): each Active Job's Board and its Work Items.
-- A Board is keyed by its Job and carries the Job code of its keys ({JOBCODE}-{n}, AD-33), unique per
-- Product, and the next number. J7 seeds one Story per Acceptance Criterion of the Agreement in force,
-- linked to the criterion's stable ID and its Milestone; that link, a Work Item's key, and a seeded
-- Story's type and Milestone never change (AD-33). No Customer endpoint reads this schema (AD-39).
-- Personal data: a Product's deletion deletes its Boards; erasing an account clears it as assignee
-- (WorkPersonalData).

CREATE SCHEMA work;

GRANT USAGE ON SCHEMA work TO ${runtime_role};

CREATE TABLE work.board
(
    job_id      UUID PRIMARY KEY,
    product_id  UUID        NOT NULL,
    code        TEXT        NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9]{1,5}$'),
    next_number INTEGER     NOT NULL DEFAULT 1 CHECK (next_number BETWEEN 1 AND 1000000),
    created_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (product_id, code)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON work.board TO ${runtime_role};

CREATE TABLE work.work_item
(
    id                  UUID PRIMARY KEY,
    job_id              UUID        NOT NULL REFERENCES work.board (job_id),
    number              INTEGER     NOT NULL CHECK (number BETWEEN 1 AND 999999),
    type                TEXT        NOT NULL CHECK (type IN ('STORY', 'TASK', 'BUG')),
    title               TEXT        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 2000),
    description         TEXT        NOT NULL DEFAULT '' CHECK (char_length(description) <= 10000),
    criterion_id        UUID,
    milestone_id        UUID,
    status              TEXT        NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')),
    assignee_account_id UUID,
    priority            TEXT CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    estimate_hours      NUMERIC(6, 1) CHECK (estimate_hours > 0),
    version             BIGINT      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, number),
    CHECK (criterion_id IS NULL OR (type = 'STORY' AND milestone_id IS NOT NULL))
);

CREATE UNIQUE INDEX work_item_criterion_idx ON work.work_item (job_id, criterion_id) WHERE criterion_id IS NOT NULL;

CREATE INDEX work_item_assignee_idx ON work.work_item (assignee_account_id) WHERE assignee_account_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON work.work_item TO ${runtime_role};

CREATE FUNCTION work.keep_fixed_fields() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.criterion_id IS DISTINCT FROM OLD.criterion_id OR NEW.job_id <> OLD.job_id OR NEW.number <> OLD.number
        OR (OLD.criterion_id IS NOT NULL
            AND (NEW.type <> OLD.type OR NEW.milestone_id IS DISTINCT FROM OLD.milestone_id)) THEN
        RAISE EXCEPTION 'a Work Item''s key and criterion link, and a seeded Story''s type and Milestone, never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER work_item_fixed_fields
    BEFORE UPDATE ON work.work_item
    FOR EACH ROW EXECUTE FUNCTION work.keep_fixed_fields();
