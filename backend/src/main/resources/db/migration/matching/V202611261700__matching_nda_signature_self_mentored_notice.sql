-- FR-81, FR-85, R68, Story 25.3: in a Self-mentored Job the commitment a Crew member signs includes N-35 (they work under
-- the Customer, who may contact them in the Crew Chat, and an Administrator deciding a Dispute may read that chat). It
-- is part of the commitment, so its text version is recorded with each signature, once per Job the person signs and
-- never once per account. NULL for a signature of a normal Job.
-- Personal data: the person's own signature; exported and deleted with the row (MatchingPersonalData).

ALTER TABLE matching.nda_signature
    ADD COLUMN self_mentored_notice_version TEXT CHECK (char_length(self_mentored_notice_version) BETWEEN 1 AND 20);

-- The signature's upsert sets it on a row a former selected Mentor already holds (V202611021200 narrowed UPDATE).
GRANT UPDATE (self_mentored_notice_version) ON matching.nda_signature TO ${runtime_role};
