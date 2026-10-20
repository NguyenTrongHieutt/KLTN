-- FR-60, FR-49: an Administrator hides a Rating that breaks policy, with the reason its rater reads; no
-- screen or API shows it, and no summary counts it.

ALTER TABLE reputation.rating
    ADD COLUMN hidden_at     TIMESTAMPTZ,
    ADD COLUMN hidden_reason TEXT CHECK (char_length(hidden_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT rating_hidden_check CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL));

GRANT UPDATE (hidden_at, hidden_reason) ON reputation.rating TO ${runtime_role};
