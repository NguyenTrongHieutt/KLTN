-- Story 21.7 (AD-37, SG-63): Teams are retired, so their four tables go, and with them every Team, membership,
-- invitation, join request, and Crew reservation. The empty schema and this folder stay as the module's shell.
-- Personal data: rows dropped, so the module needs no participant; the audit entries hold internal IDs only (AD-8).

DROP TABLE team.crew_reservation;

DROP TABLE team.team_request;

DROP TABLE team.membership;

DROP TABLE team.team;
