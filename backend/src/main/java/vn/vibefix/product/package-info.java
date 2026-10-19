/**
 * Layer 5 (AD-3): Products and their Source Repositories (FR-7, AD-13). A Customer registers a
 * Product from a public GitHub repository they administer, checked live with their own sign-in
 * token through {@code identity}; the repository is kept by GitHub's numeric ID (NFR-5), and the
 * CUS-3 acknowledgments are recorded with their notice versions. Scans (Story 2.3) and Job pins
 * (Epic 3) arrive later.
 * <p>
 * No public API yet; the HTTP endpoints are wiring in {@code product.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "github" })
package vn.vibefix.product;

import org.springframework.modulith.ApplicationModule;
