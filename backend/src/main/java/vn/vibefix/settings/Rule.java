package vn.vibefix.settings;

/**
 * The rule values of FR-61, with their bounds; the database holds the same bounds, and the PRD
 * defaults (addendum B2) are seeded by the module's migration.
 */
public enum Rule {

	/** Days after a Milestone Handover before it is accepted automatically (FR-35); 7. */
	AUTO_ACCEPTANCE_DAYS(1, 30),

	/** The day of the first auto-acceptance reminder (FR-35); 3. */
	AUTO_ACCEPTANCE_FIRST_REMINDER_DAY(1, 30),

	/** The day of the second auto-acceptance reminder (FR-35); 6. */
	AUTO_ACCEPTANCE_SECOND_REMINDER_DAY(1, 30),

	/** Days the Rating Window stays open (FR-49); 14. */
	RATING_WINDOW_DAYS(1, 30),

	/** Revision Rounds per Milestone unless the Agreement says otherwise (FR-34); 2. */
	REVISION_ROUNDS(0, 10),

	/** Days a side has to answer a Dispute (FR-52); 5. */
	DISPUTE_RESPONSE_DAYS(1, 30),

	/** Days the Mentor has to answer a Team Issue (FR-79); 5. */
	TEAM_ISSUE_ANSWER_DAYS(1, 30),

	/** Days a Crew member has to escalate a Team Issue (FR-79); 5. */
	TEAM_ISSUE_ESCALATION_DAYS(1, 30),

	/** Days before an unanswered closure proposal may become a Dispute (FR-39); 5. */
	CLOSURE_CONFIRMATION_DAYS(1, 30),

	/** Days before an unanswered cancellation may become a Dispute (FR-40); 5. */
	CANCELLATION_CONFIRMATION_DAYS(1, 30),

	/** Days after the closure proposal before closure is confirmed automatically (FR-67); 7. */
	AUTOMATIC_CLOSURE_DAYS(1, 30),

	/** Days after entering Closing that the Final Merge authorization is accepted (FR-37); 7. */
	FINAL_MERGE_AUTHORIZATION_DAYS(1, 30),

	/** Days a Job may stay in Agreement before either side may cancel alone (FR-40); 14. */
	AGREEMENT_STALL_DAYS(7, 30),

	/** Days a Job may stay in Starting before either side may cancel alone (FR-40); 7. */
	STARTING_STALL_DAYS(3, 30),

	/** Jobs a Mentor may hold from Selecting through Closing (FR-13); 3. */
	MENTOR_ACTIVE_JOB_LIMIT(1, 10),

	/** The largest share a Mentor may take in Crew Terms, in whole percent (FR-75, AD-41); 40. */
	MENTOR_SHARE_CAP_PERCENT(10, 100),

	/** Days a Crew Application waits for the Mentor before it lapses (FR-61, FR-81, R6, Story 21.4); 14. */
	CREW_APPLICATION_LAPSE_DAYS(1, 30);

	private final int min;

	private final int max;

	Rule(int min, int max) {
		this.min = min;
		this.max = max;
	}

	public int min() {
		return this.min;
	}

	public int max() {
		return this.max;
	}

}
