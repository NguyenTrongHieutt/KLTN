-- Story 23.11b (R6, R17, AD-11, AD-12, FR-18, FR-21): an Assessment Job's one private copy is read-only for everybody
-- who is invited to it. Story 23.11a made that copy; this migration marks the grants on it. A read-only grant is the
-- Mentor's, a Crew member's or the Customer's read of an Intermediary Repository that no path may ever raise: not
-- Verify, not the crew-change raise, not the 10-minute sweep, and not a caller written next year. The column says so on
-- the row, and job_grant_read_only_check refuses the WRITE status and a write_at on it, so "nobody ever gets write" is
-- an invariant of the table rather than a rule each caller must remember; it follows the shape of
-- job_grant_customer_read_check, which says the same of the Customer's read whatever the Job's kind. Every other CHECK
-- stays as it is.
-- Old rows: every grant there is belongs to a Code Job's Working Repository or to a Customer's read, none of them
-- read-only by this column, so the default false is right for all of them.
-- Personal data: none; read_only says what a grant may do, not who holds it. The grant's own personal data (the
-- account, the GitHub username and the numeric ID) are unchanged and stay covered by RepoPersonalData.

ALTER TABLE repo.job_grant
    ADD COLUMN read_only BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT job_grant_read_only_check CHECK (NOT read_only OR (status <> 'WRITE' AND write_at IS NULL));
