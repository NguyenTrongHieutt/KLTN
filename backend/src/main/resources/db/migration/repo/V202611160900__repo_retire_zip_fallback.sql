-- Story 23.10c (FR-37, FR-38, D10, AD-5, AD-8, R16): the ZIP Fallback is retired. Nothing builds, stores or serves
-- a ZIP of vibefix's own any more: the Customer receives the code from the Intermediary Repository, by the Final
-- Merge or by their own code receipt (Story 23.10a), and the closing choice's deadline ends a Job that confirms
-- neither (Story 23.10b). repo.zip_fallback has no reader and no writer left, so the table goes with its index, its
-- GRANT and its reference to repo.final_merge_authorization.
--
-- The two end reasons that named the retired paths go with it. DECLINED has been unreachable since Story 23.10a
-- ("Nhận mã dạng ZIP" is gone) and ZIP_FALLBACK becomes unreachable here, because a refused Final Merge no longer
-- ends the row at all: it stays REQUESTED so the FR-37 deadline keeps running and the Job still leaves CLOSING.
-- A row holding one of them belongs to a Job that has already ended: its state, its ended_at and its audit entries
-- all stay (AD-8), and only the reason -- which names a path that no longer exists -- becomes NULL. end_reason is
-- nullable in the row, the view and the screen, so nothing reads a missing reason as a missing ending.
--
-- That is why the CHECK pairing ENDED with a reason is weakened rather than kept: an ENDED row may now hold no
-- reason at all, which is exactly what these historical rows say. The other half still holds and is re-added
-- named -- a reason implies an ended row -- and every reason the code writes (PROPOSAL_ENDED at a proposal that
-- ended, CANCELLED at J10, DEADLINE at the closing choice's deadline) is still one of the three allowed.
--
-- repo.job_setup.archive_file_id and the final ZIP of Story 7.7 are not touched here: they belong to the 30-day
-- deletion, which Story 23.10d retires.
-- Personal data: the deleted rows hold a Job id, a Product id and a reason, and the archive they named is the
-- Setup's, which stays; nothing new is kept.

DROP TABLE IF EXISTS repo.zip_fallback;

-- The CHECKs that name end_reason go by their definition, whatever their names: the list of reasons, and the
-- pairing with ENDED that would refuse the rows below.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN SELECT conname FROM pg_constraint
                 WHERE conrelid = 'repo.final_merge_authorization'::regclass AND contype = 'c'
                   AND pg_get_constraintdef(oid) LIKE '%end_reason%'
    LOOP
        EXECUTE format('ALTER TABLE repo.final_merge_authorization DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;

UPDATE repo.final_merge_authorization SET end_reason = NULL WHERE end_reason IN ('ZIP_FALLBACK', 'DECLINED');

ALTER TABLE repo.final_merge_authorization
    ADD CONSTRAINT final_merge_authorization_end_reason_check
        CHECK (end_reason IN ('PROPOSAL_ENDED', 'CANCELLED', 'DEADLINE')),
    ADD CONSTRAINT final_merge_authorization_ended_check
        CHECK (end_reason IS NULL OR status = 'ENDED');
