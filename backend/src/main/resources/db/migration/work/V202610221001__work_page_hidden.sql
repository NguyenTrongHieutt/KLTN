-- FR-60: an Administrator hides a Page that breaks policy, with the reason its creator reads; no one
-- reads it, or its children, any more.

ALTER TABLE work.page
    ADD COLUMN hidden_at     TIMESTAMPTZ,
    ADD COLUMN hidden_reason TEXT CHECK (char_length(hidden_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT page_hidden_check CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL));
