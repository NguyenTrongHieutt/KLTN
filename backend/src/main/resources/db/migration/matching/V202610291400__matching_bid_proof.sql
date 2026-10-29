-- Story 21.2 (FR-13, AD-20, AD-21, AD-24, X3): a bid's proof of competence, at most five items in the bidder's order,
-- each a FILE that files stores (purpose matching.bid_proof: an image or a PDF) or an https LINK, with a caption of 1 to
-- 200 characters. A file is uploaded before the bid is sent and waits for it unattached (application_id, position and
-- caption NULL) until "Gửi chào giá" attaches it; a link exists only on a bid. Only the bidder and the Job's Customer
-- read a bid's proof (AD-20). When a bid ends unselected (closed, not selected, withdrawn or lapsed) its proof is due 90
-- days later (application.proof_due_at), and a scheduled purge deletes the due rows and their files with an audit entry.
-- Personal data: exported for the bidder; deleted with the bidder's or the Customer's erasure, the Product, and the
-- Job's data at the end of its retention (MatchingPersonalData).

CREATE TABLE matching.bid_proof
(
    id                UUID PRIMARY KEY,
    application_id    UUID REFERENCES matching.application (id),
    job_id            UUID        NOT NULL,
    product_id        UUID        NOT NULL,
    bidder_account_id UUID        NOT NULL,
    position          INTEGER CHECK (position BETWEEN 1 AND 5),
    kind              TEXT        NOT NULL CHECK (kind IN ('FILE', 'LINK')),
    file_id           UUID UNIQUE,
    file_name         TEXT CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes        BIGINT CHECK (size_bytes >= 0),
    url               TEXT CHECK (char_length(url) BETWEEN 1 AND 2000),
    caption           TEXT CHECK (char_length(caption) BETWEEN 1 AND 200),
    added_at          TIMESTAMPTZ NOT NULL,
    UNIQUE (application_id, position),
    CHECK ((kind = 'FILE') = (file_id IS NOT NULL AND file_name IS NOT NULL AND size_bytes IS NOT NULL)),
    CHECK ((kind = 'LINK') = (url IS NOT NULL)),
    CHECK (kind = 'FILE' OR application_id IS NOT NULL),
    CHECK ((application_id IS NULL) = (position IS NULL)),
    CHECK ((application_id IS NULL) = (caption IS NULL))
);

CREATE INDEX bid_proof_pending_idx ON matching.bid_proof (job_id, bidder_account_id) WHERE application_id IS NULL;

CREATE INDEX bid_proof_bidder_idx ON matching.bid_proof (bidder_account_id);

CREATE INDEX bid_proof_product_idx ON matching.bid_proof (product_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON matching.bid_proof TO ${runtime_role};

-- X3: when an ended bid's proof is due for deletion, 90 days after it ended; bids that ended before this story are due
-- 90 days after their end too.
ALTER TABLE matching.application
    ADD COLUMN proof_due_at TIMESTAMPTZ;

UPDATE matching.application
SET proof_due_at = ended_at + INTERVAL '90 days'
WHERE ended_at IS NOT NULL;

CREATE INDEX application_proof_due_idx ON matching.application (proof_due_at) WHERE proof_due_at IS NOT NULL;
