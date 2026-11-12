-- Story 23.1 (D8, R16, AD-18, AD-24): vibefix scans no code any more. The scan runs, their Findings and the
-- Findings' resolvers go, with every row they hold; there is no production data. The empty schema stays, as
-- scanning keeps its rules file (scanning/rules.yaml) for the FR-47 text checks and chat redaction, and the
-- Sensitive Change classifier, neither of which stores anything.

DROP TABLE scanning.finding_resolver;

DROP TABLE scanning.finding;

DROP TABLE scanning.scan_run;
