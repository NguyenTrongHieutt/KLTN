/**
 * Layer 7 (AD-3): each Active Job's Board and its Work Items (FR-22 to FR-26, FR-76, AD-39). J7 seeds one
 * Story per Acceptance Criterion of the Agreement in force (AD-33), keyed {@code {JOBCODE}-{n}}
 * ({@link vn.vibefix.shared.WorkItemKey}). Only the Job's Mentor and its Crew read a Board, as {@code job}
 * says (AD-41); no Customer endpoint returns Work Items (AD-39).
 * <p>
 * No public API yet; the listener, the Board read, and the personal-data participant are wiring in
 * {@code work.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "job", "product" })
package vn.vibefix.work;

import org.springframework.modulith.ApplicationModule;
