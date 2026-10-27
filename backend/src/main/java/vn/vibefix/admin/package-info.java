/**
 * Layer 11 (AD-3): the Administrator area's own screens. It reads the other modules' queues through
 * their public APIs ({@code operations}, {@code shared}, and {@code identity}'s Administrators and accounts),
 * alerts every Administrator to what the lower modules only record (including
 * {@code github}'s configuration drift), and owns the Abuse Reports users file (FR-69, Story 16.1): the owning
 * modules say what a user can see ({@link vn.vibefix.shared.ReportableContent}), and a report on a chat message
 * or a Work Item note pushes a grant on that one item down to {@code chat} and {@code work} (AD-20).
 * <p>
 * Public API: none. The HTTP endpoints are wiring in {@code admin.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "operations", "notification", "settings", "catalog", "scanning", "identity", "job", "dispute",
		"reputation", "github" })
package vn.vibefix.admin;

import org.springframework.modulith.ApplicationModule;
