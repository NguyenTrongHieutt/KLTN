-- AD-36: the one text search configuration for keyword search. It is a copy of simple, so words
-- are only lower-cased, with platform.unaccent first, so "bao mat" matches "Bảo mật". Each module
-- builds its generated tsvector columns with it and queries them with websearch_to_tsquery; a module
-- migration may name it read-only (MigrationConventionsTest's allowlist).

CREATE TEXT SEARCH CONFIGURATION platform.vi_search (COPY = pg_catalog.simple);

ALTER TEXT SEARCH CONFIGURATION platform.vi_search
    ALTER MAPPING FOR asciiword, asciihword, hword_asciipart, word, hword, hword_part
    WITH platform.unaccent, pg_catalog.simple;
