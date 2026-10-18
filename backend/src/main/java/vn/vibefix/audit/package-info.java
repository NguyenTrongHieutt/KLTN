/**
 * Layer 1 (AD-3): the Audit Log (AD-8, FR-63). Every state change, operation result, and
 * Administrator read of protected content is recorded through {@link vn.vibefix.audit.AuditLog} in
 * the caller's transaction. Entries hold internal IDs and codes only, so the module holds no
 * personal data and needs no {@code PersonalDataParticipant} (AD-24); nothing edits, deletes, or
 * purges them.
 * <p>
 * Public API: {@link vn.vibefix.audit.AuditLog}, {@link vn.vibefix.audit.AuditEntry},
 * {@link vn.vibefix.audit.AuditActor} and {@link vn.vibefix.audit.AuditObject}.
 */
@ApplicationModule(allowedDependencies = "shared")
package vn.vibefix.audit;

import org.springframework.modulith.ApplicationModule;
