/**
 * Layer 0 (AD-3): types every module may use. {@code shared} depends on no other module.
 * <p>
 * Public API:
 * <ul>
 * <li>Errors and lists (AD-23): {@link vn.vibefix.shared.ErrorCode}, {@link vn.vibefix.shared.ApiException},
 * {@link vn.vibefix.shared.RateLimitedException} (AD-34),
 * {@link vn.vibefix.shared.PageResponse}; money: {@link vn.vibefix.shared.Money}; IDs:
 * {@link vn.vibefix.shared.IdGenerator}.</li>
 * <li>Authorization (AD-20): the caller ({@link vn.vibefix.shared.Caller} of a {@link vn.vibefix.shared.Kind},
 * {@link vn.vibefix.shared.CurrentCaller}, and the transitional {@link vn.vibefix.shared.Role}), the endpoint gate
 * ({@link vn.vibefix.shared.Access}, {@link vn.vibefix.shared.Gate}), the refusals of a module's policy
 * ({@link vn.vibefix.shared.Policies}), and the Administrator read-grant contract
 * ({@link vn.vibefix.shared.AdminReadGrants}, {@link vn.vibefix.shared.AdminReadGrant},
 * {@link vn.vibefix.shared.AdminReadItem}, {@link vn.vibefix.shared.AdminReadScope}).</li>
 * <li>Personal data (AD-24): {@link vn.vibefix.shared.PersonalDataParticipant} and its types, run
 * through {@link vn.vibefix.shared.PersonalData}.</li>
 * <li>Domain events (AD-9): {@link vn.vibefix.shared.DomainEvent}, {@link vn.vibefix.shared.ProcessedEvents},
 * {@link vn.vibefix.shared.StalledEventPublications}.</li>
 * <li>The sandbox job channel (AD-32): {@link vn.vibefix.shared.SandboxJobs},
 * {@link vn.vibefix.shared.SandboxJobHandler}, {@link vn.vibefix.shared.SandboxJobRequest},
 * {@link vn.vibefix.shared.SandboxJobResult} and {@link vn.vibefix.shared.SandboxJobException}.</li>
 * </ul>
 * <p>
 * Everything in {@code shared.internal} (the {@link java.time.Clock} bean, the ID generator, the
 * Problem Details advice, the environment and secret-source guards, the correlation-ID and
 * security-header filters, the access gate, the personal-data fan-out, the event registry upkeep,
 * the sandbox worker and its HTTP client) is wiring that no other module references.
 */
@ApplicationModule(allowedDependencies = {})
package vn.vibefix.shared;

import org.springframework.modulith.ApplicationModule;
