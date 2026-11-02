-- Story 21.7 (AD-7, AD-37): Team invitations and join requests are gone, and so is the handler of their lapse. A
-- pending Deadline of that kind would fail every poll forever, so its rows go, pending or finished.

DELETE FROM timers.deadline WHERE kind = 'team.request_lapse';
