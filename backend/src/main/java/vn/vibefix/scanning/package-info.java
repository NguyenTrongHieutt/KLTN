/**
 * Layer 4 (AD-3, AD-18): the Secret Scan (FR-8). A scan is requested in the transaction of the change
 * that causes it ({@link vn.vibefix.scanning.Scans#requestScan}), runs as an External Operation in the
 * scan lane, and reads the repository's history inside the sandbox (AD-32) with the RE2/J rules of
 * {@code scanning/rules.yaml}. Findings keep a masked preview and an HMAC fingerprint, never the
 * credential; a completed run publishes {@link vn.vibefix.scanning.ScanCompleted} and notifies the
 * Customer. No AI is involved and no repository content leaves the platform.
 * <p>
 * Public API: {@link vn.vibefix.scanning.Scans} and its records. The operation, the sandbox job, and
 * the stores are wiring in {@code scanning.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "operations", "notification" })
package vn.vibefix.scanning;

import org.springframework.modulith.ApplicationModule;
