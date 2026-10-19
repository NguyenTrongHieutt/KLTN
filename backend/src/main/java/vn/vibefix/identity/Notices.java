package vn.vibefix.identity;

import java.util.UUID;

/**
 * Notices a person acknowledges once per notice version (AD-19), recorded with the time: N-23 before
 * the first NDA or Crew assignment of a Code Job (FR-18, PRD §6.5 #16).
 */
public interface Notices {

	/** N-23: commits pushed to the public repositories publish their author's name and email. */
	String COMMIT_EMAIL = "COMMIT_EMAIL";

	/** N-23's current version. */
	String COMMIT_EMAIL_VERSION = "1.0";

	boolean acknowledged(UUID accountId, String notice, String version);

	/** Records the acknowledgment in the caller's transaction; acknowledging again changes nothing. */
	void acknowledge(UUID accountId, String notice, String version);

}
