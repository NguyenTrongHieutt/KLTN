/**
 * Layer 4 (AD-3, AD-18): deterministic rules, with no AI. vibefix scans no code (Story 23.1): the Customer confirms at
 * J7 that their history is clean, and the Mentor keeps the team's secrets out. Nothing checks, masks or refuses the
 * text people send (D5, Story 24.4).
 * <ul>
 * <li>{@link vn.vibefix.scanning.SensitiveChanges} classifies the paths a handover or the Final Merge changes (AD-33),
 * and names the {@code .github/workflows/} folder a handover may not change.</li>
 * <li>{@link vn.vibefix.scanning.CredentialCheck} applies the credential rules of {@code scanning/rules.yaml} to one
 * short value, so {@code job} refuses an External Access identifier that looks like a credential (FR-66). The rules
 * match GitHub's stateless {@code ghs_} tokens.</li>
 * </ul>
 * Public API: those two interfaces. The rules and their matcher are wiring in {@code scanning.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.scanning;

import org.springframework.modulith.ApplicationModule;
