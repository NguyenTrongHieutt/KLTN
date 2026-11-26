-- FR-85, AD-47 (Story 25.2a): the version of notice N-34 the Customer read when they made the Job self-mentored, by
-- publishing it (J11) or by switching it (J12). N-34 says that the Customer leads the Crew themself, that applicants see
-- the Customer's name and profile, that the Customer gets no access to the Working Repository, and that the Customer
-- takes no share of the Milestone money. A normal Job holds none; a self-mentored Job holds one from the moment it
-- leaves its Draft, so the acknowledgement is on record with the Job, as the Product's notices are with the Product.
-- Personal data: none.

ALTER TABLE job.job
    ADD COLUMN self_mentored_notice_version TEXT
        CHECK (self_mentored_notice_version IS NULL OR self_mentored),
    ADD CONSTRAINT job_self_mentored_notice_check
        CHECK (NOT self_mentored OR state = 'DRAFT' OR self_mentored_notice_version IS NOT NULL);
