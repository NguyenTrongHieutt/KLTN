-- Schema platform: what keeps the event publication registry replay-safe and watched (AD-9).
-- Runs as the migration role; the runtime role ${runtime_role} gets DML only, table by table.

-- A listener's record that it processed an event, written in the listener's transaction, so a
-- replayed publication changes nothing. Purged after vibefix.events.processed-retention.
CREATE TABLE platform.processed_event
(
    listener     TEXT        NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (listener, event_id)
);

CREATE INDEX processed_event_processed_at_idx ON platform.processed_event (processed_at);

GRANT SELECT, INSERT, DELETE ON platform.processed_event TO ${runtime_role};

-- Publications that failed every attempt, for the Administrator queue (FR-64). The mark goes once
-- its publication completes or is gone.
CREATE TABLE platform.event_publication_stalled
(
    publication_id UUID PRIMARY KEY,
    stalled_at     TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, DELETE ON platform.event_publication_stalled TO ${runtime_role};
