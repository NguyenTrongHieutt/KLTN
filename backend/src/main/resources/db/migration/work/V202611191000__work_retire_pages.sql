-- Story 24.1 (D2, FR-29, FR-30, FR-31, FR-70, AD-21, AD-23): the Documentation Space is retired, so the platform
-- manages no Pages at all. The three tables go with their indexes and their constraints: work.page_share first (it
-- references work.board, not work.page), then work.page_version, then work.page itself, whose parent_id references
-- itself. V202610210500, V202610210600, V202610221001 and V202610231500 are superseded; they stay as Flyway history.
--
-- What this loses: every Page, every version of one, each Page's template badge and visibility flag, the hidden-Page
-- reason an Administrator wrote, and which earlier Jobs a Customer shared their customer-visible Pages with. An
-- Assessment Job's Report went this way too: from now on such a Job hands over its Milestone, whose Acceptance
-- Criteria the Assessment Report template already writes (job/assessment-report-template.yaml), and Story 24.3 gives
-- the handover the Mentor's own document link. The work schema itself stays: its Board and Work Items are Story 24.2's.
-- The audit entries of earlier shares and reads stay, as AD-8 keeps them.
--
-- The Administrator read grants go with it: AdminReadScope has no PAGES any more, and WorkGrants calls
-- AdminReadScope.valueOf on each stored item, so a grant naming it could not be read back at all. A Dispute's grant
-- is the only one that ever held PAGES, beside the Job Chat, Work Items, their notes and the Attachment list, so
-- stripping that one item leaves every such grant with four and none of them empty; chat and job strip their own.
-- Personal data: rows deleted; nothing new is kept. These three tables leave WorkPersonalData's export, its erasure
-- pass and its Product-deletion pass in the same story, so no participant is left naming a table that is gone.

DROP TABLE work.page_share;

DROP TABLE work.page_version;

DROP TABLE work.page;

UPDATE work.admin_read_grant
SET items = (SELECT jsonb_agg(item) FROM jsonb_array_elements(items) AS item WHERE item ->> 'scope' <> 'PAGES')
WHERE items @> '[{"scope": "PAGES"}]';
