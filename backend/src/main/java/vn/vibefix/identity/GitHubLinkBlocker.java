package vn.vibefix.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Something that keeps an account from unlinking GitHub (FR-80, R14, AD-19): a live Product, an open bid, or a
 * role in a Job that is not Closed or Cancelled. Unlinking waits until no participant reports one. A re-link must
 * return the same numeric ID in every case, blocker or not.
 * @param module the module reporting it
 * @param reason the module's code: {@code LIVE_PRODUCT}, {@code JOB_ROLE}, or {@code OPEN_BID}
 * @param subjectId the Product or the Job it concerns
 * @param name what ACC-2 calls it: the Product's name or the Job's title
 */
public record GitHubLinkBlocker(String module, String reason, UUID subjectId, String name) {

	public GitHubLinkBlocker {
		Objects.requireNonNull(module, "module");
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(subjectId, "subjectId");
		Objects.requireNonNull(name, "name");
	}

}
