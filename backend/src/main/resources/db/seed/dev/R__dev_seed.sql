-- SG-36: dev seed data. Flyway reads db/seed/dev only in the dev profile (application-dev.yaml), and
-- the prod profile refuses to start when this location is configured, before any migration runs.
--
-- A repeatable migration: Flyway applies it after the versioned migrations and again whenever this
-- file changes, as the migration role. Seed rows arrive with the stories that create their tables;
-- each must be synthetic (AD-25) and safe to re-apply (INSERT ... ON CONFLICT DO NOTHING), and must
-- qualify every name with its module's schema.

-- Story 1.9: school domains for trying the school email code on the dev stack, where every email
-- lands in Mailpit. truong.test is the synthetic one for walk-throughs; OQ8 decides the real list.
INSERT INTO settings.school_domain (domain, added_at)
VALUES ('truong.test', now()),
       ('hust.edu.vn', now()),
       ('vnu.edu.vn', now()),
       ('hcmut.edu.vn', now()),
       ('hcmus.edu.vn', now())
ON CONFLICT DO NOTHING;
