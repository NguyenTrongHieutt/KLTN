-- Story 21.5 review: no caller of Teams.releaseCrew remains now that picks replace the Team picker, so a reservation
-- would hold its membership forever and TeamMemberships.requireNoCrew would answer STUDENT_ON_CREW. Story 21.7 retires
-- the code and the table.

DELETE FROM team.crew_reservation;
