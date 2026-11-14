-- Schema delivery (FR-25, FR-32, N-33, R28, Story 23.8b): the Mentor's duty confirmation, recorded with the
-- handover it was sent for. vibefix scans no team code, so before "Gửi bàn giao" the Mentor ticks N-33's Mentor
-- checkbox -- the handover's commits hold no secret, password or real data, and a pushed key was rotated or
-- revoked -- and the request names the notice version the form showed. The version is checked against
-- vibefix.delivery.team-code-notice-version before anything is drafted (VALIDATION NOTICE_REQUIRED), and these
-- three columns keep what was confirmed: who ticked it, when, and under which version of the text.
-- An Assessment Job hands over no code, so it neither shows N-33 nor records it: all three stay NULL. A Code
-- handover recorded before this story has none either, so no CHECK ties them to handover_commit.
-- Personal data: team_code_account_id names the confirming Mentor, who is the handover's own Mentor. An erasure
-- clears it as it clears mentor_account_id, and a Product deletion deletes the handover rows (DeliveryPersonalData,
-- which already covers delivery.handover).

ALTER TABLE delivery.handover
    ADD COLUMN team_code_account_id     UUID,
    ADD COLUMN team_code_notice_version TEXT CHECK (team_code_notice_version ~ '^[0-9]{1,3}\.[0-9]{1,3}$'),
    ADD COLUMN team_code_confirmed_at   TIMESTAMPTZ,
    ADD CONSTRAINT handover_team_code_check CHECK ((team_code_notice_version IS NULL) = (team_code_confirmed_at IS NULL)
        AND (team_code_account_id IS NULL OR team_code_notice_version IS NOT NULL));

CREATE INDEX handover_team_code_account_idx ON delivery.handover (team_code_account_id)
    WHERE team_code_account_id IS NOT NULL;

-- delivery.handover grants UPDATE per column, as it has since delivery_init: a confirmation never changes, but
-- for an erasure, which clears who ticked it and leaves the version and the time.
GRANT UPDATE (team_code_account_id) ON delivery.handover TO ${runtime_role};
