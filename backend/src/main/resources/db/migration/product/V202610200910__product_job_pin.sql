-- FR-7, AD-3: the Jobs that pin a Product. job pins a Product while a Job of it is in Selecting,
-- Agreement, Starting, Active, or Closing (Epic 3), and a pinned Product cannot be deleted.

CREATE TABLE product.job_pin
(
    product_id UUID        NOT NULL REFERENCES product.product (id) ON DELETE CASCADE,
    job_id     UUID        NOT NULL,
    pinned_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_id, job_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.job_pin TO ${runtime_role};
