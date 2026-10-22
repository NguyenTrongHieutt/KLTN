/**
 * Disputes (FR-52, FR-54, AD-31): the Customer or the Mentor opens one on a running Job, which freezes
 * its money and pausable Deadlines (§4.0 freeze rule) until an Administrator decides and the System
 * executes the decision (Story 9.5). The other side gets a statement period (Story 15.1), and the assignee
 * reads the Evidence Package, assembled on demand from the owning modules' queries and the grant pushed down
 * to {@code chat} and {@code work}, each view audited (Story 15.2).
 * <p>
 * Team Issues (FR-79, AD-40, Tier 3), kept apart from Disputes: a Crew member raises one with the Job's
 * Mentor about their share of a Held Milestone, which holds the two parties' parts through {@code payment},
 * or about the Rating the Mentor gave them. They never freeze the Job, and the Customer never sees them.
 * <p>
 * Public API: {@link vn.vibefix.dispute.DisputeQueue}, the Disputes awaiting a decision for ADM-1. The
 * HTTP endpoints, the execution, and the personal-data participant are wiring in {@code dispute.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "operations", "timers", "job",
		"payment", "reputation", "delivery", "repo" })
package vn.vibefix.dispute;

import org.springframework.modulith.ApplicationModule;
