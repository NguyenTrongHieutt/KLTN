/**
 * Layer 7 (AD-3): the Job Chat between a Job's Customer and its Mentor (FR-46) and the Crew Chat
 * between the Mentor and the Crew (FR-77, AD-41), both opened by J4 and made read-only by J9 or J10.
 * A message is stored as written: nothing checks, masks or refuses it for what it says, and the
 * Job's end rewrites none (Story 24.4a). Only the participants read a Chat; an Administrator reads one
 * only under a grant a case module pushed down ({@link vn.vibefix.shared.AdminReadGrants}), each read
 * audited (AD-20, AD-8).
 * <p>
 * Public API: {@link vn.vibefix.chat.ChatEvidence}, how far the Job Chat reached, for a Dispute decision's
 * evidence snapshot (SG-27), and the Crew Chat of a Milestone's interval, for a Split Evidence Package. The HTTP endpoints, the listener, the grants, the file read policy, and the
 * personal-data participant are wiring in {@code chat.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "files", "identity", "notification", "job" })
package vn.vibefix.chat;

import org.springframework.modulith.ApplicationModule;
