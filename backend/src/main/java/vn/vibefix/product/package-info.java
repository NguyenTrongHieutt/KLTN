/**
 * Layer 5 (AD-3): Products and their Source Repositories (FR-7, AD-13). A Customer registers a
 * Product from a public GitHub repository they administer, checked live with their own sign-in
 * token through {@code identity}; the repository is kept by GitHub's numeric ID (NFR-5), and the
 * CUS-3 acknowledgments are recorded with their notice versions. Registration requests the intake
 * scan from {@code scanning} in its own transaction, and CUS-2 and CUS-4 show the scan and its
 * Findings. A Customer deletes a Product no Job pins; every participant deletes its data for it.
 * <p>
 * Public API: {@link vn.vibefix.product.ProductPins} (Job pins, Epic 3) and
 * {@link vn.vibefix.product.ProductDeleted}. The HTTP endpoints are wiring in {@code product.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "github", "scanning" })
package vn.vibefix.product;

import org.springframework.modulith.ApplicationModule;
