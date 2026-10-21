/**
 * Layer 7 (AD-3): the platform repositories of Code Jobs, their access, drift, Final Merge, and the
 * Revocation Checklist (Epic 7). J7's Setup (Story 7.2) copies the Source Repository at the commit a
 * {@code J7} scan approved into the Intermediary Repository, forks it into the Working Repository, and
 * reads the rulesets back, under the Job's busy marker. It also keeps the post-Job data-deletion confirmations
 * (FR-66): after J8 or J10 the Mentor and every Crew member who was ever active confirm, item by item,
 * that they deleted the Customer's non-code data and their preview deployments; closure never waits for
 * it.
 * <p>
 * Public API: {@link vn.vibefix.repo.DataDeletions}, whether a person confirmed (FR-50), and
 * {@link vn.vibefix.repo.DataDeletionConfirmed}, the event when their confirmation completes.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "job", "operations", "github",
		"product", "scanning" })
package vn.vibefix.repo;

import org.springframework.modulith.ApplicationModule;
