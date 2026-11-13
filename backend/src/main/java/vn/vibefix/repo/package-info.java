/**
 * Layer 7 (AD-3): the platform repositories of Code Jobs, their access, drift, Final Merge, and the
 * Revocation Checklist (Epic 7). J7's Setup (Story 7.2) waits, with no busy marker, for the Customer to
 * self-confirm the Source Repository's default-branch head (vibefix scans no code, Story 23.6b); then, under the
 * Job's busy marker, it creates the private Intermediary and Working Repositories and copies exactly that head into
 * both on the Customer's own GitHub Actions, through their Source App, and reads both back. It also keeps the
 * post-Job data-deletion confirmations
 * (FR-66): after J8 or J10 the Mentor and every Crew member who was ever active confirm, item by item,
 * that they deleted the Customer's non-code data and their preview deployments; closure never waits for
 * it. Revocation and deletion (Story 7.7) end every grant after J8, J10, a lock, or a Crew change, and
 * delete both repositories 30 days after the end, once the final ZIP is stored through {@code files}.
 * <p>
 * Public API: {@link vn.vibefix.repo.DataDeletions}, whether a person confirmed (FR-50),
 * {@link vn.vibefix.repo.DataDeletionConfirmed}, the event when their confirmation completes, and
 * {@link vn.vibefix.repo.HandoverBranches}, a handover's range and its pinned branch (FR-32, Story 8.2).
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "files", "identity", "notification", "job", "operations",
		"github", "product", "scanning", "timers" })
package vn.vibefix.repo;

import org.springframework.modulith.ApplicationModule;
