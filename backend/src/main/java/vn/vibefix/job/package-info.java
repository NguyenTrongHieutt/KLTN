/**
 * Layer 6 (AD-3): Jobs (FR-9). A Customer drafts a Job on one of their Products by answering guided
 * plain-language questions, choosing Job Types and Skills, and attaching screenshots; only its
 * Customer reads or edits a Draft. The Job lifecycle, Job Posts, and Applications arrive with the
 * stories of Epic 3.
 * <p>
 * No public API yet; the HTTP endpoints, the Skill-name listener, and the screenshot read policy are
 * wiring in {@code job.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "files", "scanning", "product" })
package vn.vibefix.job;

import org.springframework.modulith.ApplicationModule;
