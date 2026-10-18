#!/usr/bin/env bash
# Runs once, when the postgres container initializes an empty volume (docker-entrypoint-initdb.d).
# Creates the two vibefix roles (Conventions, Migrations; AD-8), with the same attributes as
# backend/src/test/resources/db/init-roles.sql, and the database vibefix owned by the migration role.
# Passwords come only from the mounted secret files (AD-22): psql reads them itself (\set with a
# backquoted cat), so they never appear in a command line, and the server log never shows them.
set -Eeo pipefail

psql --no-psqlrc --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\set app_password `cat /run/secrets/db-app-password`
\set migrator_password `cat /run/secrets/db-migrator-password`
CREATE ROLE vibefix_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'migrator_password';
CREATE ROLE vibefix_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'app_password';
CREATE DATABASE vibefix OWNER vibefix_migrator;
SQL
