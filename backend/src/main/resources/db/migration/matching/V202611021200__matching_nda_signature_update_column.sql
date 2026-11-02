-- Story 21.5 review: matching.nda_signature is evidence, and the signature's upsert changes one column of it, so the
-- table-wide UPDATE of V202610291810 narrows to commitment_version.

REVOKE UPDATE ON matching.nda_signature FROM ${runtime_role};
GRANT UPDATE (commitment_version) ON matching.nda_signature TO ${runtime_role};
