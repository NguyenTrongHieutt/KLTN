/**
 * Layer 7 (AD-3): the Job Chat between a Job's Customer and its Mentor (FR-46) and the Crew Chat
 * between the Mentor and the Crew (FR-77, AD-41), both opened by J4 and made read-only by J9 or J10.
 * Before a message is stored, {@code scanning}'s rules check it for likely
 * credentials and bulk personal data (FR-47); the sender masks, edits, or sends anyway. Only the
 * participants read a Chat; an Administrator reads one only under a grant a case module pushed down
 * ({@link vn.vibefix.shared.AdminReadGrants}), each read audited (AD-20, AD-8).
 * <p>
 * No public API yet; the HTTP endpoints, the listener, the grants, the file read policy, and the
 * personal-data participant are wiring in {@code chat.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "files", "scanning", "identity", "notification", "job" })
package vn.vibefix.chat;

import org.springframework.modulith.ApplicationModule;
