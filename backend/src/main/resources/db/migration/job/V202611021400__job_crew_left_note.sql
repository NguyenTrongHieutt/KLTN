-- Story 21.6 (FR-82, AD-41): the reason a member gave when leaving, up to 500 characters and only on a row released
-- LEFT by the member: a lock's release writes none. The Job's Mentor alone reads it on JOB-9's Crew list, and it reaches
-- no notification, to-do, or other view. Personal data: exported to its author, cleared by an erasure and by the
-- retention run (JobPersonalData).

ALTER TABLE job.crew_row
    ADD COLUMN left_note TEXT;

ALTER TABLE job.crew_row
    ADD CONSTRAINT crew_row_left_note_check
        CHECK (left_note IS NULL
            OR (released_reason IS NOT DISTINCT FROM 'LEFT' AND char_length(left_note) BETWEEN 1 AND 500));
