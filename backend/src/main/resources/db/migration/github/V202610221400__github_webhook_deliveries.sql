-- Schema github (AD-12, SG-18): the delivery IDs of the GitHub webhooks whose signature checked out,
-- so a delivery GitHub sends again (its own retry, or the daily redelivery) is published once. Kept 30
-- days, longer than the 3 days the redelivery looks back.
-- Personal data: none; a delivery ID, the App, and the event name identify nobody.

CREATE SCHEMA github;

GRANT USAGE ON SCHEMA github TO ${runtime_role};

CREATE TABLE github.webhook_delivery
(
    delivery_id TEXT PRIMARY KEY CHECK (length(delivery_id) BETWEEN 1 AND 100),
    app         TEXT        NOT NULL CHECK (app IN ('PLATFORM', 'MERGE')),
    event       TEXT        NOT NULL CHECK (length(event) BETWEEN 1 AND 100),
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX webhook_delivery_received_idx ON github.webhook_delivery (received_at);

GRANT SELECT, INSERT, DELETE ON github.webhook_delivery TO ${runtime_role};
