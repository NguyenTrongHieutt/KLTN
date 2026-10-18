/**
 * Layer 0 (AD-3): types every module may use. {@code shared} depends on no other module.
 * <p>
 * Public API: {@link vn.vibefix.shared.ErrorCode}, {@link vn.vibefix.shared.ApiException},
 * {@link vn.vibefix.shared.Money}, {@link vn.vibefix.shared.PageResponse}, and the sandbox job
 * channel (AD-32): {@link vn.vibefix.shared.SandboxJobs}, {@link vn.vibefix.shared.SandboxJobHandler},
 * {@link vn.vibefix.shared.SandboxJobRequest}, {@link vn.vibefix.shared.SandboxJobResult} and
 * {@link vn.vibefix.shared.SandboxJobException}.
 * <p>
 * Everything in {@code shared.internal} (the {@link java.time.Clock} bean, the Problem Details
 * advice, the environment and secret-source guards, the correlation-ID and security-header filters,
 * the sandbox worker and its HTTP client) is wiring that no other module references.
 */
@ApplicationModule(allowedDependencies = {})
package vn.vibefix.shared;

import org.springframework.modulith.ApplicationModule;
