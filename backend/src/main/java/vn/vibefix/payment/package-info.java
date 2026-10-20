/**
 * Layer 7 (AD-14, AD-15): test-mode money through the VNPay sandbox. Only the sandbox host is ever
 * called, the application refusing to start otherwise, and the merchant's hash secret is a secret file
 * (AD-22). Story 4.5 adds the sandbox client and spike S2's {@code spike-s2} probe in
 * {@code payment.internal}; Payment Orders, the IPN, and the ledger come with Stories 4.6 and 4.7.
 * <p>
 * Public API: none yet.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.payment;

import org.springframework.modulith.ApplicationModule;
