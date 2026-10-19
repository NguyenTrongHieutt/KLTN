-- Crew reservations (AD-37, AD-41): job pushes one down for each Crew row it creates, and removes it
-- when the row is released. While a membership has one, the Student can neither leave nor be removed,
-- and the Mentor-gone or lock release keeps them until the reservation ends. No personal data of its
-- own: it names a membership and a Job.
CREATE TABLE team.crew_reservation
(
    crew_row_id   UUID PRIMARY KEY,
    membership_id UUID        NOT NULL REFERENCES team.membership (id),
    job_id        UUID        NOT NULL,
    reserved_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX crew_reservation_membership_idx ON team.crew_reservation (membership_id);

GRANT SELECT, INSERT, DELETE ON team.crew_reservation TO ${runtime_role};
