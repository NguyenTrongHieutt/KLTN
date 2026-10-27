-- Story 19.7 (AD-21, AD-24, AD-42): the student cards and Mentor application documents have no owner and
-- no read policy any more. Their rows are marked deleted, so nothing serves, exports, or counts them; their
-- bytes stay on the dev volume until the author's reset removes them.

UPDATE files.stored_file SET deleted_at = now()
WHERE purpose IN ('identity.student_card', 'identity.mentor_document') AND deleted_at IS NULL;
