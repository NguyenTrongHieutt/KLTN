-- Story 20.3 (AD-19, FR-73): an account holds no role, so a new Skill suggestion records no suggester role. The
-- column stays for the suggestions made before, and its CHECK still holds for them; a NULL passes it.
-- Personal data: unchanged (AdminPersonalData).

ALTER TABLE admin.skill_suggestion
    ALTER COLUMN suggester_role DROP NOT NULL;
