-- Story 24.3a (D3, FR-10, FR-32, AD-32, AD-35): a Milestone Handover carries up to 10 https links, each of kind
-- VIDEO ("Bản ghi / video") or DOCUMENT ("Tài liệu") with a short label the Mentor wrote, in the order the Mentor
-- listed them (position 0 to 9). The Customer reads them beside the criteria in the Delivery Report and the guided
-- check, and the Mentor and the active Crew read the same report. A link is never fetched: no preview, no title, no
-- liveness check; the screens show the domain computed from the URL's text. Only the preview link is checked, as
-- before, and it stays on delivery.handover.
--
-- The recording link V202610222000 kept in a column of its own becomes one of these links: each handover that held
-- recording_url gets a VIDEO link at position 0 labelled "Video quay màn hình" (the plain term screenRecordingLink),
-- and the column goes. Its preview check is rewritten without it: a preview link carries its check time, and a
-- preview and a no-preview reason never come together. That a reason comes with at least one VIDEO link, and that
-- an Assessment Job's handover carries a DOCUMENT link (its report, since the platform stores no documents), are the
-- handover's own rules (Handovers.draft), since a CHECK cannot see another table. V202610222000 is superseded in
-- these two places and stays as Flyway history; delivery.handover_screenshot is untouched.
--
-- A Code Job's pending handover keeps its draft in delivery.handover_submission (V202611141430), as the Java record
-- Handovers.Pending serialised it: every stored draft loses recordingUrl and gains links, the recording as that same
-- VIDEO link, so a draft written before this story reads back with the links it meant.
-- Personal data: none new. A link is what the Mentor wrote on the handover, like its notes; it names no account.
-- Retirement and Product deletion delete the links with what each handover said, and the Mentor's export lists the
-- links of the handovers they made (DeliveryPersonalData).

CREATE TABLE delivery.handover_link
(
    handover_id UUID    NOT NULL REFERENCES delivery.handover (id),
    position    INTEGER NOT NULL CHECK (position BETWEEN 0 AND 9),
    kind        TEXT    NOT NULL CHECK (kind IN ('VIDEO', 'DOCUMENT')),
    url         TEXT    NOT NULL CHECK (url ~ '^https://\S+$' AND char_length(url) <= 500),
    label       TEXT    NOT NULL CHECK (char_length(label) BETWEEN 1 AND 80),
    PRIMARY KEY (handover_id, position)
);

INSERT INTO delivery.handover_link (handover_id, position, kind, url, label)
SELECT id, 0, 'VIDEO', recording_url, 'Video quay màn hình'
FROM delivery.handover
WHERE recording_url IS NOT NULL;

ALTER TABLE delivery.handover
    DROP CONSTRAINT handover_preview_check,
    DROP COLUMN recording_url,
    ADD CONSTRAINT handover_preview_check CHECK ((preview_url IS NULL) = (preview_checked_at IS NULL)
        AND (preview_url IS NULL OR no_preview_reason IS NULL));

UPDATE delivery.handover_submission
SET draft = (draft - 'recordingUrl') || jsonb_build_object('links',
        CASE
            WHEN jsonb_typeof(draft -> 'recordingUrl') = 'string' THEN jsonb_build_array(jsonb_build_object(
                    'kind', 'VIDEO', 'url', draft ->> 'recordingUrl', 'label', 'Video quay màn hình'))
            ELSE '[]'::jsonb
        END)
WHERE jsonb_typeof(draft) = 'object';

GRANT SELECT, INSERT, DELETE ON delivery.handover_link TO ${runtime_role};
