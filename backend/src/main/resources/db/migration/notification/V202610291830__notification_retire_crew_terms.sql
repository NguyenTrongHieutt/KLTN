-- Story 21.5 (AD-27, FR-81, UX-DR112): the Crew comes from Crew Applications and Crew Terms are no longer written, so
-- the to-dos of the Team-based picker (job.pick_crew, now matching.open_crew_recruitment) and of the Crew Terms
-- confirmation (job.confirm_crew_terms, now matching.sign_commitment), and the notifications of the Crew Terms and the
-- Crew changes, have no type and no text any more: their rows go, as Story 19.7 removed the Mentor approval's.
-- Personal data: rows deleted; nothing new is kept.

DELETE FROM notification.todo WHERE todo_key IN ('job.pick_crew', 'job.confirm_crew_terms');

DELETE FROM notification.notification
WHERE type IN ('job.crew_assigned', 'job.crew_member_confirmed', 'job.crew_member_declined', 'job.crew_member_removed',
               'job.crew_terms_changed', 'job.crew_change_proposed', 'job.crew_changed', 'job.crew_change_voided',
               'job.crew_confirmed', 'job.crew_reconfirm_needed');
