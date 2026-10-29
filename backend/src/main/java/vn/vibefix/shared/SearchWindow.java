package vn.vibefix.shared;

import java.time.Duration;
import java.util.UUID;

/**
 * The one keyword-search limit (NFR-3, AD-34, AD-36): at most {@value #LIMIT} keyword searches per account in any
 * minute, shared by every search a person runs, the Job Posts of "Tìm việc" and the people of CUS-8 "Tìm người hướng
 * dẫn" alike (Story 21.3). Browsing by filter alone is no search.
 */
public interface SearchWindow {

	int LIMIT = 60;

	Duration WINDOW = Duration.ofMinutes(1);

	/**
	 * Counts one search by the account.
	 * @throws RateLimitedException when it would be the 61st in a minute, retried once the oldest counted search
	 * leaves the window
	 */
	void record(UUID accountId);

}
