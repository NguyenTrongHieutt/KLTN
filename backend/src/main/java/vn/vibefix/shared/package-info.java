/**
 * Layer 0 (AD-3): types every module may use. {@code shared} depends on no other module.
 * <p>
 * Public API: {@link vn.vibefix.shared.ErrorCode}, {@link vn.vibefix.shared.ApiException},
 * {@link vn.vibefix.shared.Money}, {@link vn.vibefix.shared.PageResponse}. Everything in
 * {@code shared.internal} (the {@link java.time.Clock} bean, the Problem Details advice, the
 * environment guard) is wiring that no other module references.
 */
@ApplicationModule(allowedDependencies = {})
package vn.vibefix.shared;

import org.springframework.modulith.ApplicationModule;
