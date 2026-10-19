/**
 * Layer 9 (AD-3, AD-33): Ratings and Portfolio Entries. Story 3.3 opens the module with the one
 * definition of an account's visible Rating summary, which Job Posts show for their Customer (FR-12);
 * the Ratings themselves arrive with the Rating Window (FR-49), so no account has a visible Rating yet.
 * <p>
 * Public API: {@link vn.vibefix.reputation.Reputation} and {@link vn.vibefix.reputation.RatingSummary}.
 * The HTTP endpoint is wiring in {@code reputation.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "identity", "job" })
package vn.vibefix.reputation;

import org.springframework.modulith.ApplicationModule;
