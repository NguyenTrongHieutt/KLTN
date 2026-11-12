/**
 * Layer 4 (AD-3, AD-18 "Rules for text checks and Sensitive Changes"): the deterministic rules of
 * {@code scanning/rules.yaml}, with no AI. vibefix scans no code (Story 23.1): the Customer confirms at
 * J7 that their history is clean, and the Mentor keeps the team's secrets out.
 * <ul>
 * <li>{@link vn.vibefix.scanning.SensitiveText} checks text about to be sent (FR-47): the Job Chat, the
 * Crew Chat, Pages, Work Items, notes, and text Attachments warn before sending what it finds, and a
 * closed Job's chats are redacted with it. The rules match GitHub's stateless {@code ghs_} tokens.</li>
 * <li>{@link vn.vibefix.scanning.SensitiveChanges} classifies the paths a handover or the Final Merge
 * changes (AD-33), and names the {@code .github/workflows/} folder a handover may not change.</li>
 * </ul>
 * Public API: those two interfaces. The rules and their matchers are wiring in {@code scanning.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.scanning;

import org.springframework.modulith.ApplicationModule;
