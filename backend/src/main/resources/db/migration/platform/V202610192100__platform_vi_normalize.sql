-- AD-38, SG-50: one accent- and case-insensitive folding for Vietnamese text. The unique Skill
-- names, the Skill suggestion duplicate check, and the Skill picker's filter all compare through
-- it; the SPA's viNormalize mirrors it (frontend/src/lib/vi-normalize.fixtures.json).
--
-- unaccent is a trusted extension, so the database owner (the migration role) may create it; it
-- lives in schema platform, and every name below is schema-qualified, so the function needs no
-- search_path and survives pg_dump and restore. A module migration may reference it read-only
-- (MigrationConventionsTest's allowlist). platform.vi_search (AD-36) arrives with the first
-- search story, in its own platform migration.

CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA platform;

-- lower(unaccent(text)), trimmed, each run of whitespace one space. The SQL-standard body is
-- parsed at creation, so its references are bound now, not looked up at each call.
CREATE FUNCTION platform.vi_normalize(value text) RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
RETURN pg_catalog.btrim(pg_catalog.regexp_replace(
    pg_catalog.lower(platform.unaccent('platform.unaccent'::regdictionary, value)), '\s+', ' ', 'g'));

GRANT EXECUTE ON FUNCTION platform.vi_normalize(text) TO ${runtime_role};
