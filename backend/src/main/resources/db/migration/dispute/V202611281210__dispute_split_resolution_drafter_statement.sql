-- FR-84, AD-40, AD-47 (Story 25.6): in a Self-mentored Job the split's drafter is the Customer, who is no party of the
-- split and so has no party row, yet writes the drafter's one statement on a Split Resolution as the Mentor does in a
-- normal Job. A statement is therefore either a party's (party_id set) or the drafter's (party_id empty), one of each
-- kind per case: the primary key over (resolution_id, party_id) gives way to one unique constraint that treats two
-- empty party_id as equal, so a case holds at most one drafter's statement too.
-- Personal data: unchanged. The statement's text and account_id are what DisputePersonalData already exports and clears.

ALTER TABLE dispute.split_resolution_statement
    DROP CONSTRAINT split_resolution_statement_pkey,
    ALTER COLUMN party_id DROP NOT NULL,
    ADD COLUMN drafter BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT split_resolution_statement_author CHECK (drafter = (party_id IS NULL)),
    ADD CONSTRAINT split_resolution_statement_one_each UNIQUE NULLS NOT DISTINCT (resolution_id, party_id);
