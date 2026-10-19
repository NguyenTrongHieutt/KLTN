/**
 * Layer 11 (AD-3): the Administrator area's own screens. It reads the other modules' queues through
 * their public APIs and owns no data yet; later stories add the decisions it records (AD-8).
 * <p>
 * Public API: none. The HTTP endpoints are wiring in {@code admin.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "operations" })
package vn.vibefix.admin;

import org.springframework.modulith.ApplicationModule;
