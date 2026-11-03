-- Story 22.4 (AD-5, AD-40, FR-64): nothing refuses a Team Issue's ending any more, so the dispute.team_issue_ending
-- operation and its handler are gone. A queued, running or failed one would make the worker throw on a type it no
-- longer knows, so it goes; a finished one is history.

DELETE FROM operations.external_operation
WHERE type = 'dispute.team_issue_ending' AND status IN ('PENDING', 'RUNNING', 'FAILED');
