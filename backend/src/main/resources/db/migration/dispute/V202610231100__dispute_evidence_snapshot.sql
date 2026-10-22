-- SG-27, AD-31, AD-24 (Story 15.3): what a decision was based on, written with it: the IDs, versions, counts,
-- and totals of the Evidence Package's sources, never copies of their content. The decision and its snapshot
-- outlive the NFR-10 retirement of the Job's data. A decision recorded before this story has none.
-- Personal data: none (IDs, versions, counts, totals, and times; no account IDs).

ALTER TABLE dispute.decision
    ADD COLUMN evidence_snapshot JSONB;
