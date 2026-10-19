-- Schema team (AD-37, FR-6): one Team per approved Mentor, its memberships, and the invitations and
-- join requests between a Team and a Student. Accounts are referenced by ID only (AD-2). Personal
-- data: exported, and deleted on erasure (TeamPersonalData).

CREATE SCHEMA team;

GRANT USAGE ON SCHEMA team TO ${runtime_role};

-- A Mentor's Team, created when the Mentor is approved; CLOSED once the Mentor is gone.
CREATE TABLE team.team
(
    id                UUID PRIMARY KEY,
    mentor_account_id UUID        NOT NULL UNIQUE,
    status            TEXT        NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    created_at        TIMESTAMPTZ NOT NULL,
    closed_at         TIMESTAMPTZ,
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- A Student's membership; it ends when they leave, are removed, or are released.
CREATE TABLE team.membership
(
    id                 UUID PRIMARY KEY,
    team_id            UUID        NOT NULL REFERENCES team.team (id),
    student_account_id UUID        NOT NULL,
    joined_at          TIMESTAMPTZ NOT NULL,
    left_at            TIMESTAMPTZ,
    left_reason        TEXT CHECK (left_reason IN ('LEFT', 'REMOVED', 'RELEASED')),
    CHECK ((left_at IS NULL) = (left_reason IS NULL))
);

-- One Team per Student at a time (AD-37).
CREATE UNIQUE INDEX membership_one_team_idx ON team.membership (student_account_id) WHERE left_at IS NULL;

CREATE INDEX membership_team_idx ON team.membership (team_id) WHERE left_at IS NULL;

-- An invitation (from the Mentor) or a join request (from the Student); it lapses after 14 days.
CREATE TABLE team.team_request
(
    id                 UUID PRIMARY KEY,
    team_id            UUID        NOT NULL REFERENCES team.team (id),
    student_account_id UUID        NOT NULL,
    kind               TEXT        NOT NULL CHECK (kind IN ('INVITATION', 'JOIN_REQUEST')),
    status             TEXT        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'LAPSED', 'CANCELLED')),
    created_at         TIMESTAMPTZ NOT NULL,
    lapses_at          TIMESTAMPTZ NOT NULL,
    answered_at        TIMESTAMPTZ,
    CHECK ((status = 'PENDING') = (answered_at IS NULL)),
    CHECK (lapses_at > created_at)
);

-- One pending request of either kind between a Team and a Student.
CREATE UNIQUE INDEX team_request_one_pending_idx ON team.team_request (team_id, student_account_id)
    WHERE status = 'PENDING';

CREATE INDEX team_request_student_idx ON team.team_request (student_account_id, created_at);

CREATE INDEX team_request_team_idx ON team.team_request (team_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON team.team, team.membership, team.team_request TO ${runtime_role};
