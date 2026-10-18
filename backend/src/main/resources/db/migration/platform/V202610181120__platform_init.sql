-- Schema platform: framework tables (AD-2) and the environment row (AD-25).
-- Runs as the migration role, which owns the schema; Flyway creates it before this migration.
-- The runtime role ${runtime_role} gets DML only, table by table.

GRANT USAGE ON SCHEMA platform TO ${runtime_role};

-- AD-25: exactly one row, naming the environment this database belongs to. The application
-- refuses to start unless it equals the active profile. The runtime role may only read it.
CREATE TABLE platform.environment
(
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    name      TEXT    NOT NULL CHECK (name IN ('dev', 'prod'))
);

INSERT INTO platform.environment (name)
VALUES ('${environment}');

GRANT SELECT ON platform.environment TO ${runtime_role};

-- AD-9: Spring Modulith's event publication registry. Copied from spring-modulith-events-jdbc 2.1.1,
-- org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql, qualified with schema
-- platform (spring.modulith.events.jdbc.schema) and without IF NOT EXISTS.
CREATE TABLE platform.event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON platform.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON platform.event_publication (completion_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.event_publication TO ${runtime_role};
