/**
 * Layer 7 (AD-14, AD-15): test-mode money through the VNPay sandbox. Only the sandbox host is ever
 * called, the application refusing to start otherwise, and the merchant's hash secret is a secret file
 * (AD-22). A Payment Order funds one Milestone; only the IPN changes state, through {@code job}'s
 * non-throwing M1 cascade, and money is an append-only ledger whose unapplied FUNDs are refunded
 * through {@code RefundGateway}: VNPay's refund call since spike S2, or ledger-recorded as a fallback.
 * Spike S2's {@code spike-s2} probe is wiring in {@code payment.internal}.
 * <p>
 * Public API: {@link vn.vibefix.payment.Ledger} (releasable and released money, AD-15) and
 * {@link vn.vibefix.payment.Releases} (the split release of an accepted Milestone, FR-42).
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "notification", "timers", "operations", "job" })
package vn.vibefix.payment;

import org.springframework.modulith.ApplicationModule;
