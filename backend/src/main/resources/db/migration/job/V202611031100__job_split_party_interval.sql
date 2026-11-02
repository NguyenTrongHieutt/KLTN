-- FR-84, AD-24, AD-40 (Story 22.3): a Split Resolution's evidence must outlive the Job's own rows, and a Product deletion
-- takes job.milestone and job.crew_row while a split is open. So the release now stores what the Split Evidence Package
-- shows: when the Milestone was funded, on the split, and each party's active interval, on the party. Both are fixed at the
-- release and never recomputed. A split whose Milestone or Crew row went before this migration keeps them empty.
-- Personal data: instants beside a party row an erasure already anonymizes (JobPersonalData).

ALTER TABLE job.milestone_split
    ADD COLUMN funded_at TIMESTAMPTZ;

ALTER TABLE job.milestone_split_party
    ADD COLUMN active_from TIMESTAMPTZ,
    ADD COLUMN active_to   TIMESTAMPTZ;

UPDATE job.milestone_split s
SET funded_at = m.funded_at
FROM job.milestone m
WHERE m.id = s.milestone_id;

UPDATE job.milestone_split_party p
SET active_from = s.funded_at,
    active_to   = s.released_at
FROM job.milestone_split s
WHERE s.id = p.split_id
  AND p.is_mentor;

UPDATE job.milestone_split_party p
SET active_from = r.activated_at,
    active_to   = least(coalesce(r.window_end, s.released_at), s.released_at)
FROM job.milestone_split s,
     job.crew_row r
WHERE s.id = p.split_id
  AND NOT p.is_mentor
  AND r.job_id = s.job_id
  AND r.member_account_id = p.party_account_id
  AND r.activated_at IS NOT NULL;
