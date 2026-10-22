-- Story 8.3 (FR-32, AD-21, AD-35): a handover carries the https preview link the Mentor checked, with the
-- time its check passed, or, when a preview is impossible, the reason and an external https recording
-- link; never both. Its screenshots are images the Mentor uploaded through files; an upload is the
-- uploader's alone until "Gửi bàn giao" attaches it, then the Job's Customer, Mentor, and Crew read it.
-- Personal data: the uploader of a screenshot; erasure deletes their unattached uploads and clears them
-- as uploader of attached ones; a Product's deletion deletes the files and rows (DeliveryPersonalData).

ALTER TABLE delivery.handover
    ADD COLUMN preview_url        TEXT CHECK (preview_url ~ '^https://\S+$' AND char_length(preview_url) <= 500),
    ADD COLUMN preview_checked_at TIMESTAMPTZ,
    ADD COLUMN recording_url      TEXT CHECK (recording_url ~ '^https://\S+$' AND char_length(recording_url) <= 500),
    ADD COLUMN no_preview_reason  TEXT CHECK (char_length(no_preview_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT handover_preview_check CHECK ((preview_url IS NULL) = (preview_checked_at IS NULL)
        AND (preview_url IS NULL OR recording_url IS NULL)
        AND (recording_url IS NULL) = (no_preview_reason IS NULL));

CREATE TABLE delivery.handover_screenshot
(
    file_id     UUID PRIMARY KEY,
    job_id      UUID        NOT NULL,
    product_id  UUID        NOT NULL,
    account_id  UUID,
    file_name   TEXT        NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    size_bytes  BIGINT      NOT NULL CHECK (size_bytes > 0),
    handover_id UUID REFERENCES delivery.handover (id),
    position    INTEGER CHECK (position BETWEEN 0 AND 4),
    uploaded_at TIMESTAMPTZ NOT NULL,
    CHECK ((handover_id IS NULL) = (position IS NULL)),
    CHECK (handover_id IS NOT NULL OR account_id IS NOT NULL)
);

CREATE INDEX handover_screenshot_job_idx ON delivery.handover_screenshot (job_id);

CREATE INDEX handover_screenshot_handover_idx ON delivery.handover_screenshot (handover_id) WHERE handover_id IS NOT NULL;

CREATE INDEX handover_screenshot_account_idx ON delivery.handover_screenshot (account_id) WHERE account_id IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON delivery.handover_screenshot TO ${runtime_role};
