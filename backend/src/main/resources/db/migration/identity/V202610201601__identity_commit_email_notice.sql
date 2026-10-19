-- N-23 (FR-18, PRD §6.5 #16): before signing the NDA for a Code Job, a Mentor (and later each Crew
-- member) acknowledges that commits pushed to the public repositories publish their name and email.
-- It is asked once per account and notice version, so it joins the acknowledgments as COMMIT_EMAIL.

ALTER TABLE identity.acknowledgment
    DROP CONSTRAINT acknowledgment_notice_check,
    ADD CONSTRAINT acknowledgment_notice_check CHECK (notice IN ('PRIVACY', 'COMMIT_EMAIL'));
