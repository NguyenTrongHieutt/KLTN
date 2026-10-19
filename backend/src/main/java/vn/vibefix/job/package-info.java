/**
 * Layer 6 (AD-3): Jobs (FR-9) and their lifecycle (PRD section 4.0, AD-4). A Customer drafts a Job on
 * one of their Products by answering guided plain-language questions, choosing Job Types and Skills,
 * and attaching screenshots, then publishes it as a Job Post (J1), which returns to Draft when
 * withdrawn or when its selection period lapses (J2). One transition table holds J1 to J10 and M1 to
 * M8, and {@code JobLifecycle} is the only code that changes a state.
 * <p>
 * Public API: {@link vn.vibefix.job.JobTransitioned}. The HTTP endpoints, the transition steps, the
 * selection lapse, the recipient lookup, the Skill-name listener, and the screenshot read policy are
 * wiring in {@code job.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "catalog", "files", "identity", "notification",
		"settings", "timers", "scanning", "product" })
package vn.vibefix.job;

import org.springframework.modulith.ApplicationModule;
