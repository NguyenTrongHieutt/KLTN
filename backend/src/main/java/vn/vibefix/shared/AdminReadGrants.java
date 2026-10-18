package vn.vibefix.shared;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The Administrator read-grant contract (AD-20, AD-3 push-down grant). The modules holding Job
 * content ({@code chat}, {@code work}, {@code job}) implement it in their own stories; only the
 * owning case modules ({@code dispute}, {@code admin}) call it, when an Administrator takes a case,
 * is reassigned, or decides. {@code AdminReadGrantCallersTest} enforces who calls.
 * <p>
 * An implementation stores each grant as an {@link AdminReadGrant}, answers {@code NOT_FOUND} to a
 * read no grant permits (outside its items, after its end, by another Administrator, or on another
 * Job), and audits every read a grant permits (AD-8). {@code AdminReadGrantsContract} in the test
 * sources checks exactly that.
 */
public interface AdminReadGrants {

	/**
	 * Opens a grant. Granting an existing {@code grantId} again changes nothing.
	 * @param reasonId the Dispute, escalated Team Issue, or Abuse Report the grant serves
	 */
	void grantAdminRead(UUID grantId, UUID adminId, UUID jobId, Set<AdminReadItem> items, UUID reasonId);

	/** Ends a grant at {@code at} ({@link AdminReadGrant#endingAt}); an unknown grant is ignored. */
	void endAdminRead(UUID grantId, Instant at);

}
