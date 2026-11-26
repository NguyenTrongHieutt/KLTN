-- Story 25.2b (FR-85, AD-4 J12, B-18): a Customer switches an Open Job to self-mentored only while it has no open
-- bid, but a bid may commit while the switch runs. Such a bid is withdrawn with the reason JOB_SELF_MENTORED, by
-- matching's listener of J12 or by its sweep, and its bidder is told. Only the list of reasons changes.

ALTER TABLE matching.application
    DROP CONSTRAINT application_end_reason_check,
    ADD CONSTRAINT application_end_reason_check CHECK (end_reason IN ('WITHDRAWN', 'MENTOR_UNAVAILABLE',
        'JOB_WITHDRAWN', 'JOB_LAPSED', 'NOT_SELECTED', 'DECLINED', 'NOT_SIGNED', 'JOB_SELF_MENTORED'));
