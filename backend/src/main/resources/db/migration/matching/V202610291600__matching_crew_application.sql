-- Story 21.4 (FR-81, AD-3, AD-7, AD-20, AD-24, AD-43, R5, R6, X3): a Crew Application, which a user with GitHub linked
-- sends from FND-2 to a Job whose Mentor recruits: a note to the Mentor, the Skills (catalog IDs with their names when
-- sent, AD-38) and at most five proof items (crew_application_proof), with no price and no share (R5). It copies the
-- Job's Product, Customer and Mentor, none of which changes while recruitment can be on (J3 records the Mentor before
-- J4), and a CHECK keeps the applicant apart from the Customer and the Mentor (AD-43); a trigger refuses an applicant
-- who holds an open or selected bid on the same Job, and one PENDING application per Job and applicant is a unique key.
-- The commands refuse first with GUARD_FAILED ROLE_TAKEN or CONFLICT ALREADY_APPLIED; these are only the backstop.
-- An application is PENDING until the Mentor DECLINES it ("Không chọn"), the applicant or a lock WITHDRAWS it, or it
-- LAPSES: lapses_at, the Job's copied CREW_APPLICATION_LAPSE_DAYS after it was sent, recruitment closing (the Mentor
-- turning it off, J8 or J10), or an Administrator hiding the Job Post. end_reason says which (EXPIRED,
-- RECRUITMENT_CLOSED, JOB_CLOSING, JOB_CANCELLED, JOB_HIDDEN for a lapse; WITHDRAWN or APPLICANT_UNAVAILABLE for a
-- withdrawal; DECLINED). Story 21.5 adds the pick.
-- Only the applicant and the Job's Mentor read an application and its proof (AD-20); the Customer never does. An ended
-- application and its proof are deleted 90 days after the decision (purge_due_at), or with the Job's data, and a
-- proof upload no application took within 90 days.
-- Personal data: exported for the applicant; deleted with the applicant's erasure (their open applications withdrawn
-- first and each Mentor told), the Customer's or the Mentor's erasure, the Product, and the Job's data at the end of its
-- retention (MatchingPersonalData).

CREATE TABLE matching.crew_application
(
    id                         UUID PRIMARY KEY,
    job_id                     UUID        NOT NULL,
    product_id                 UUID        NOT NULL,
    customer_account_id        UUID        NOT NULL,
    mentor_account_id          UUID        NOT NULL,
    applicant_account_id       UUID        NOT NULL,
    status                     TEXT        NOT NULL CHECK (status IN ('PENDING', 'DECLINED', 'WITHDRAWN', 'LAPSED')),
    note                       TEXT        NOT NULL CHECK (char_length(note) BETWEEN 1 AND 2000),
    skill_ids                  UUID[]      NOT NULL DEFAULT '{}',
    skill_names                TEXT[]      NOT NULL DEFAULT '{}',
    public_code_notice_version TEXT        NOT NULL,
    submitted_at               TIMESTAMPTZ NOT NULL,
    lapses_at                  TIMESTAMPTZ NOT NULL,
    ended_at                   TIMESTAMPTZ,
    end_reason                 TEXT CHECK (end_reason IN ('DECLINED', 'WITHDRAWN', 'APPLICANT_UNAVAILABLE', 'EXPIRED',
                                                          'RECRUITMENT_CLOSED', 'JOB_CLOSING', 'JOB_CANCELLED',
                                                          'JOB_HIDDEN')),
    purge_due_at               TIMESTAMPTZ,
    CONSTRAINT crew_application_not_a_role_check
        CHECK (applicant_account_id <> customer_account_id AND applicant_account_id <> mentor_account_id),
    CONSTRAINT crew_application_skills_check
        CHECK (cardinality(skill_ids) <= 10 AND cardinality(skill_ids) = cardinality(skill_names)),
    CONSTRAINT crew_application_ended_check
        CHECK ((status = 'PENDING') = (ended_at IS NULL AND end_reason IS NULL)),
    CONSTRAINT crew_application_lapse_check CHECK (lapses_at > submitted_at)
);

CREATE UNIQUE INDEX crew_application_pending_idx ON matching.crew_application (job_id, applicant_account_id)
    WHERE status = 'PENDING';

CREATE INDEX crew_application_applicant_idx ON matching.crew_application (applicant_account_id, submitted_at DESC);

CREATE INDEX crew_application_job_idx ON matching.crew_application (job_id, submitted_at);

CREATE INDEX crew_application_product_idx ON matching.crew_application (product_id);

CREATE INDEX crew_application_purge_idx ON matching.crew_application (purge_due_at) WHERE purge_due_at IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON matching.crew_application TO ${runtime_role};

-- AD-43: a bidder whose bid on the Job is still open or was selected holds, or may still take, the Mentor's role there,
-- so they never apply to its Crew; a bidder not selected may (R3).
CREATE FUNCTION matching.refuse_crew_application_of_a_bidder() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM matching.application a
               WHERE a.job_id = NEW.job_id AND a.mentor_account_id = NEW.applicant_account_id
               AND a.status IN ('SUBMITTED', 'ON_HOLD', 'SELECTED')) THEN
        RAISE EXCEPTION 'one role per Job: an open or selected bidder never applies to the Job''s Crew'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER crew_application_not_a_bidder
    BEFORE INSERT OR UPDATE OF job_id, applicant_account_id ON matching.crew_application
    FOR EACH ROW EXECUTE FUNCTION matching.refuse_crew_application_of_a_bidder();

-- The application's proof, by the bid proof's rules (Story 21.2): at most five items in the applicant's order, each a
-- FILE that files stores (purpose matching.crew_application_proof: an image or a PDF) or an https LINK, with a caption
-- of 1 to 200 characters. A file is uploaded before the application is sent and waits for it unattached
-- (crew_application_id, position and caption NULL) until "Gửi đơn" attaches it; a link exists only on an application.
-- Only the applicant and the Job's Mentor read it (AD-20).

CREATE TABLE matching.crew_application_proof
(
    id                   UUID PRIMARY KEY,
    crew_application_id  UUID REFERENCES matching.crew_application (id),
    job_id               UUID        NOT NULL,
    product_id           UUID        NOT NULL,
    applicant_account_id UUID        NOT NULL,
    position             INTEGER CHECK (position BETWEEN 1 AND 5),
    kind                 TEXT        NOT NULL CHECK (kind IN ('FILE', 'LINK')),
    file_id              UUID UNIQUE,
    file_name            TEXT CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes           BIGINT CHECK (size_bytes >= 0),
    url                  TEXT CHECK (char_length(url) BETWEEN 1 AND 2000),
    caption              TEXT CHECK (char_length(caption) BETWEEN 1 AND 200),
    added_at             TIMESTAMPTZ NOT NULL,
    UNIQUE (crew_application_id, position),
    CHECK ((kind = 'FILE') = (file_id IS NOT NULL AND file_name IS NOT NULL AND size_bytes IS NOT NULL)),
    CHECK ((kind = 'LINK') = (url IS NOT NULL)),
    CHECK (kind = 'FILE' OR crew_application_id IS NOT NULL),
    CHECK ((crew_application_id IS NULL) = (position IS NULL)),
    CHECK ((crew_application_id IS NULL) = (caption IS NULL))
);

CREATE INDEX crew_application_proof_pending_idx ON matching.crew_application_proof (job_id, applicant_account_id)
    WHERE crew_application_id IS NULL;

CREATE INDEX crew_application_proof_applicant_idx ON matching.crew_application_proof (applicant_account_id);

CREATE INDEX crew_application_proof_product_idx ON matching.crew_application_proof (product_id);

CREATE INDEX crew_application_proof_application_idx ON matching.crew_application_proof (crew_application_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON matching.crew_application_proof TO ${runtime_role};
