-- FR-60: an Administrator hides a Product that breaks policy, with the reason its Customer reads. It
-- leaves the public surfaces (its Open Job Posts, portfolio names) while its running Jobs continue.

ALTER TABLE product.product
    ADD COLUMN hidden_at     TIMESTAMPTZ,
    ADD COLUMN hidden_reason TEXT CHECK (char_length(hidden_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT product_hidden_check CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL));
