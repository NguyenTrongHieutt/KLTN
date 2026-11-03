-- FR-84, AD-7, AD-24, AD-29, AD-40 (Story 22.3): a party other than the Mentor asks an Administrator to decide a Milestone
-- Split, with the whole Milestone's money held meanwhile. Each party raises at most one per Milestone, and at most one is
-- open per Milestone, whoever raised it; the raiser is kept by party, since the account may be anonymized. Every other party
-- adds one statement. A case ends WITHDRAWN or DECIDED, and a decision keeps its reasoning, its VND amounts and the IDs and
-- versions of the evidence read (AD-24, SG-27). undecided_due_at holds the time the undecided filter and alert read,
-- from the Job's copied days at the raise (AD-7).
-- No foreign key reaches job or dispute's other tables, which can go before these rows.
-- Personal data: financial records under NFR-10's legal retention. A Product deletion and the retention run never delete
-- them, so the runtime role has no DELETE; an erasure sets the people to NULL and keeps the text (DisputePersonalData).

CREATE TABLE dispute.split_resolution
(
    id                  UUID PRIMARY KEY,
    split_id            UUID        NOT NULL,
    job_id              UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    milestone_id        UUID        NOT NULL,
    milestone_number    INTEGER     NOT NULL CHECK (milestone_number > 0),
    raiser_party_id     UUID        NOT NULL,
    raiser_account_id   UUID,
    split_version_no    INTEGER     NOT NULL CHECK (split_version_no > 0),
    reason              TEXT        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 2000),
    status              TEXT        NOT NULL CHECK (status IN ('OPEN', 'WITHDRAWN', 'DECIDED')),
    raised_at           TIMESTAMPTZ NOT NULL,
    undecided_due_at    TIMESTAMPTZ NOT NULL,
    assigned_admin_id   UUID,
    assigned_at         TIMESTAMPTZ,
    grant_id            UUID,
    ended_at            TIMESTAMPTZ,
    decided_by_admin_id UUID,
    decision_version_no INTEGER CHECK (decision_version_no > 0),
    decision_reasoning  TEXT CHECK (char_length(decision_reasoning) BETWEEN 1 AND 4000),
    decision_amounts    JSONB,
    evidence_snapshot   JSONB,
    CHECK ((status = 'OPEN') = (ended_at IS NULL)),
    CHECK ((status = 'DECIDED') = (decision_version_no IS NOT NULL)),
    CHECK ((status = 'DECIDED') = (decision_reasoning IS NOT NULL)),
    CHECK ((status = 'DECIDED') = (decision_amounts IS NOT NULL)),
    CHECK ((status = 'DECIDED') = (evidence_snapshot IS NOT NULL))
);

CREATE UNIQUE INDEX split_resolution_one_per_party_idx ON dispute.split_resolution (milestone_id, raiser_party_id);

CREATE UNIQUE INDEX split_resolution_one_open_idx ON dispute.split_resolution (milestone_id) WHERE status = 'OPEN';

CREATE INDEX split_resolution_split_idx ON dispute.split_resolution (split_id);

CREATE INDEX split_resolution_queue_idx ON dispute.split_resolution (status, raised_at);

GRANT SELECT, INSERT ON dispute.split_resolution TO ${runtime_role};

GRANT UPDATE (raiser_account_id, status, assigned_admin_id, assigned_at, grant_id, ended_at, decided_by_admin_id,
    decision_version_no, decision_reasoning, decision_amounts, evidence_snapshot) ON dispute.split_resolution TO ${runtime_role};

CREATE TABLE dispute.split_resolution_statement
(
    resolution_id UUID        NOT NULL REFERENCES dispute.split_resolution (id),
    party_id      UUID        NOT NULL,
    account_id    UUID,
    text          TEXT        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 2000),
    submitted_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (resolution_id, party_id)
);

CREATE INDEX split_resolution_statement_account_idx ON dispute.split_resolution_statement (account_id);

GRANT SELECT, INSERT ON dispute.split_resolution_statement TO ${runtime_role};

GRANT UPDATE (account_id) ON dispute.split_resolution_statement TO ${runtime_role};
