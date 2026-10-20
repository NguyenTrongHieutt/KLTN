/**
 * Layer 11 (AD-3): the Administrator area's own screens. It reads the other modules' queues through
 * their public APIs ({@code operations}, {@code shared}, and {@code identity}'s Administrators and student
 * card queue) and owns no
 * data yet; later stories add the decisions it records (AD-8).
 * <p>
 * Public API: none. The HTTP endpoints are wiring in {@code admin.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "operations", "notification", "settings", "identity", "dispute", "reputation" })
package vn.vibefix.admin;

import org.springframework.modulith.ApplicationModule;
