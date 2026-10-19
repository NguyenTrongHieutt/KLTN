-- Student verification by a student card (FR-5): the Student uploads it, an Administrator approves it
-- with the card's student number and school or rejects it with a reason, and the file is deleted 30
-- days after the decision. The bytes are in files (AD-21); file_id names them, and the name and type
-- are kept here for ADM-3 after the bytes are gone. Personal data:
-- exported, and deleted on erasure (AD-24).
CREATE TABLE identity.student_card
(
    id               UUID PRIMARY KEY,
    account_id       UUID        NOT NULL REFERENCES identity.account (id),
    file_id          UUID        NOT NULL UNIQUE,
    file_name        TEXT        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 200),
    media_type       TEXT        NOT NULL CHECK (char_length(media_type) BETWEEN 3 AND 100),
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    uploaded_at      TIMESTAMPTZ NOT NULL,
    decided_at       TIMESTAMPTZ,
    decided_by       UUID REFERENCES identity.account (id),
    student_number   TEXT CHECK (char_length(student_number) BETWEEN 1 AND 30),
    school           TEXT CHECK (char_length(school) BETWEEN 1 AND 200),
    rejection_reason TEXT CHECK (char_length(rejection_reason) BETWEEN 1 AND 300),
    file_deleted_at  TIMESTAMPTZ,
    CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CHECK (status <> 'PENDING' OR decided_by IS NULL),
    CHECK ((status = 'APPROVED') = (student_number IS NOT NULL AND school IS NOT NULL)),
    CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CHECK (file_deleted_at IS NULL OR status <> 'PENDING')
);

-- A Student has at most one card waiting; ADM-3's queue reads the oldest first.
CREATE UNIQUE INDEX student_card_one_pending ON identity.student_card (account_id) WHERE status = 'PENDING';
CREATE INDEX student_card_queue ON identity.student_card (status, uploaded_at);
CREATE INDEX student_card_account ON identity.student_card (account_id, uploaded_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.student_card TO ${runtime_role};

-- A card verifies at most one account, as a school email does (FR-5): card_key is the school and
-- the student number, normalized (identity.internal.StudentCards#cardKey).
ALTER TABLE identity.student_verification DROP CONSTRAINT student_verification_method_check;
ALTER TABLE identity.student_verification
    ADD CONSTRAINT student_verification_method_check CHECK (method IN ('SCHOOL_EMAIL', 'STUDENT_CARD')),
    ADD COLUMN card_key TEXT UNIQUE CHECK (char_length(card_key) BETWEEN 3 AND 240),
    ADD COLUMN student_card_id UUID REFERENCES identity.student_card (id),
    ADD CONSTRAINT student_verification_card_check
        CHECK (method <> 'STUDENT_CARD' OR (card_key IS NOT NULL AND student_card_id IS NOT NULL));
