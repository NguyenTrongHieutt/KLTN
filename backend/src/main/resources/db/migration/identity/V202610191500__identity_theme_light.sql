-- A new account starts on the light theme of the UI frames (author, 2026-10-19; UX-DR5); dark
-- only when the account chooses "Tối", or "Theo hệ thống" on a dark system.
ALTER TABLE identity.account ALTER COLUMN theme SET DEFAULT 'LIGHT';
