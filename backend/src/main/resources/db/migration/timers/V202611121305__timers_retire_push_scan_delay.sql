-- Story 23.1 (D8, R16, AD-7): no push scan is requested any more, so the Deadline that alerted Administrators about a
-- delayed one has no handler. Its rows go, pending or finished, and the poller never meets the kind.

DELETE FROM timers.deadline WHERE kind = 'repo.push_scan_delay';
