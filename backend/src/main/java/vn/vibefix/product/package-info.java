/**
 * Layer 5 (AD-3): Products and their Source Repositories (FR-7, AD-13). A Customer registers a
 * Product from a GitHub repository, public or private, they administer, on which they installed the Source App
 * and added the published {@code vibefix.yml} (Story 23.5, AD-45); it is checked live through the Source App,
 * whose binding to the pair (installation, repository) {@code product} owns. The repository is kept by GitHub's
 * numeric ID (NFR-5), and the CUS-3 acknowledgments are recorded with their notice versions. vibefix scans no code
 * (Story 23.1), so registration starts no scan. A Customer deletes a Product no Job pins; every participant deletes
 * its data for it, and its binding ends.
 * <p>
 * Each Product keeps its last known Source App and workflow status, whatever check found it (Story 23.5c), so CUS-2
 * shows it without a GitHub call; on a change {@code product} opens or closes {@code product.install_source_app} and
 * {@code product.add_workflow} and sends {@code repo.workflow_check_failed} through {@code notification} (L1), and a
 * daily job warns of a workflow version nearing its end date (R12).
 * <p>
 * Public API: {@link vn.vibefix.product.ProductDirectory}, {@link vn.vibefix.product.ProductPins} (Job
 * pins, Epic 3), {@link vn.vibefix.product.ProductSources} (the check before a Code Job starts and before the Final
 * Merge, Story 7.2; the contributor read and the drift read through the Source App, Story 23.5b; J7's head read and
 * dispatch target, Story 23.6a), and
 * {@link vn.vibefix.product.ProductDeleted}. The HTTP endpoints, the Source App's Setup URL
 * included, are wiring in {@code product.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "notification", "catalog", "identity", "github" })
package vn.vibefix.product;

import org.springframework.modulith.ApplicationModule;
