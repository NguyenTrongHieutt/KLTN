/**
 * Layer 7 (AD-3): the platform repositories of Code Jobs, their access, drift, Final Merge, and the
 * Revocation Checklist arrive with Epic 7. It starts with the post-Job data-deletion confirmations
 * (FR-66): after J8 or J10 the Mentor and every Crew member who was ever active confirm, item by item,
 * that they deleted the Customer's non-code data and their preview deployments; closure never waits for
 * it.
 * <p>
 * Public API: {@link vn.vibefix.repo.DataDeletions}, whether a person confirmed (FR-50), and
 * {@link vn.vibefix.repo.DataDeletionConfirmed}, the event when their confirmation completes.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "job" })
package vn.vibefix.repo;

import org.springframework.modulith.ApplicationModule;
