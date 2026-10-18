-- Schema platform: server-side sessions (AD-19, Spring Session JDBC; spring.session.jdbc.table-name).
-- Copied from spring-session-jdbc 4.1.1, org/springframework/session/jdbc/schema-postgresql.sql, and
-- qualified with schema platform. The runtime role ${runtime_role} gets DML only.

CREATE TABLE platform.spring_session
(
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT   NOT NULL,
    last_access_time      BIGINT   NOT NULL,
    max_inactive_interval INT      NOT NULL,
    expiry_time           BIGINT   NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON platform.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON platform.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON platform.spring_session (principal_name);

CREATE TABLE platform.spring_session_attributes
(
    session_primary_id CHAR(36)     NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES platform.spring_session (primary_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.spring_session TO ${runtime_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.spring_session_attributes TO ${runtime_role};
