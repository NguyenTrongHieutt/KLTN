-- The two database roles of vibefix (Conventions, Migrations; AD-8).
-- vibefix_migrator owns the database and every schema, and runs Flyway.
-- vibefix_app is the runtime role; migrations grant it DML table by table.
-- No passwords here: the test harness sets random ones after start, and deployments mount their own
-- secret files (AD-22).
CREATE ROLE vibefix_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE vibefix_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
