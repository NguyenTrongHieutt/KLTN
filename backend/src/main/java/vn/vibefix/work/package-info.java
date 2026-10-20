/**
 * Layer 7 (AD-3): each Active Job's Board and its Work Items (FR-22 to FR-26, FR-76, AD-39). J7 seeds one
 * Story per Acceptance Criterion of the Agreement in force (AD-33), keyed {@code {JOBCODE}-{n}}
 * ({@link vn.vibefix.shared.WorkItemKey}); the Mentor and the active Crew create and edit Work Items
 * with a full history, and only the Mentor assigns them. Only the Job's Mentor and its Crew read a
 * Board, as {@code job} says (AD-41), a former member only their own window's; an Administrator reads
 * only under a grant a case module pushed down ({@link vn.vibefix.shared.AdminReadGrants}), each read
 * audited (AD-20). No Customer endpoint returns Work Items (AD-39).
 * <p>
 * No public API yet; the listener, the endpoints, the grants, and the personal-data participant are
 * wiring in {@code work.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "scanning", "job", "product" })
package vn.vibefix.work;

import org.springframework.modulith.ApplicationModule;
