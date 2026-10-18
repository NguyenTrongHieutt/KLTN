-- SG-36: dev seed data. Flyway reads db/seed/dev only in the dev profile (application-dev.yaml), and
-- the prod profile refuses to start when this location is configured, before any migration runs.
--
-- A repeatable migration: Flyway applies it after the versioned migrations and again whenever this
-- file changes, as the migration role. Seed rows arrive with the stories that create their tables;
-- each must be synthetic (AD-25) and safe to re-apply (INSERT ... ON CONFLICT DO NOTHING), and must
-- qualify every name with its module's schema.
--
-- Until then it deliberately does nothing.
SELECT 1;
