package vn.vibefix.identity;

import java.util.UUID;

/**
 * Notices a person acknowledges once per notice version (AD-19, AD-42), recorded with the time: N-23 before
 * the first NDA or Crew assignment of a Code Job (FR-18, PRD §6.5 #16), N-24 before linking GitHub
 * (FR-80, NFR-9), and N-26 before a school email code is sent (FR-5, FR-80).
 */
public interface Notices {

	/** N-23: commits pushed to the public repositories publish their author's name and email. */
	String COMMIT_EMAIL = "COMMIT_EMAIL";

	/** N-23's current version. */
	String COMMIT_EMAIL_VERSION = "1.0";

	/** N-24: what vibefix receives from GitHub when an account links it, and what the link then holds. */
	String LINK_GITHUB = "LINK_GITHUB";

	/** N-24's current version; the SPA shows the same one ({@code vi.json} {@code notice.github.version}). */
	String LINK_GITHUB_VERSION = "1.0";

	/** N-26: what vibefix keeps of a student email verification, and that the declared name and major lock. */
	String VERIFY_STUDENT = "VERIFY_STUDENT";

	/** N-26's current version; the SPA shows the same one ({@code vi.json} {@code notice.student.version}). */
	String VERIFY_STUDENT_VERSION = "1.0";

	boolean acknowledged(UUID accountId, String notice, String version);

	/** Records the acknowledgment in the caller's transaction; acknowledging again changes nothing. */
	void acknowledge(UUID accountId, String notice, String version);

}
