package vn.vibefix.shared;

/**
 * The kinds of Job content an Administrator may read only under a grant (AD-20). Without a grant
 * the Administrator relationship reads none of them.
 */
public enum AdminReadScope {

	/** The Job Chat ({@code chat}). */
	JOB_CHAT,

	/** The Crew Chat ({@code chat}). */
	CREW_CHAT,

	/** Work Items with their history ({@code work}). */
	WORK_ITEMS,

	/** Work Item notes ({@code work}). */
	WORK_ITEM_NOTES,

	/** The Attachment list ({@code work}). */
	ATTACHMENTS,

	/** Pages of the Documentation Space ({@code work}). */
	PAGES,

	/** Crew Terms versions ({@code job}). */
	CREW_TERMS

}
