/**
 * Disputes (FR-52, FR-54, AD-31): the Customer or the Mentor opens one on a running Job, which freezes
 * its money and pausable Deadlines (§4.0 freeze rule) until an Administrator decides and the System
 * executes the decision (Story 9.5). The Evidence Package and the statement period are Epic 15's.
 * <p>
 * Public API: {@link vn.vibefix.dispute.DisputeQueue}, the Disputes awaiting a decision for ADM-1. The
 * HTTP endpoints, the execution, and the personal-data participant are wiring in {@code dispute.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "timers", "job", "payment" })
package vn.vibefix.dispute;

import org.springframework.modulith.ApplicationModule;
