-- Story 24.5b (FR-37, AD-6, AD-11, R17): the Final Merge check names what it waits for. J8 used to request the check
-- in the same moment as the team's revocation, so it nearly always ran before the grants read back REVOKED and
-- deferred a full ten minutes. Now J8's CHECKING row waits on REVOCATION with no check requested, and the read-back
-- of the last Mentor or Crew grant requests it. A check that defers records why -- REVOCATION, CUSTOMER for a Source
-- block the Customer fixes on CUS-4, FROZEN for a dispute -- and when it looks again, which the Customer's closing
-- card shows. next_check_at is null for a revocation not yet read back, since nothing is requested to look at all.
-- Both columns are cleared when the check is queued or succeeds, and only a CHECKING row waits.
-- Old rows get null. A CHECKING row whose check had already deferred reads as checking, with no wait on the card,
-- until its next attempt records the wait; a revocation deferral from before this migration is not woken by a
-- read-back and runs at its ten minutes, as it did before.
-- Personal data: none; a reason code and a time.

ALTER TABLE repo.final_merge
    ADD COLUMN waiting_for   TEXT CHECK (waiting_for IN ('REVOCATION', 'CUSTOMER', 'FROZEN')),
    ADD COLUMN next_check_at TIMESTAMPTZ,
    -- A time belongs to a reason, and only a revocation not yet read back has none.
    ADD CONSTRAINT final_merge_wait_check CHECK ((waiting_for IS NULL OR status = 'CHECKING')
        AND (next_check_at IS NULL OR waiting_for IS NOT NULL)
        AND (waiting_for IS NULL OR waiting_for = 'REVOCATION' OR next_check_at IS NOT NULL));
