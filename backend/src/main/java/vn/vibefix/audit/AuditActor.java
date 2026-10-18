package vn.vibefix.audit;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who caused an audited change: an account, by internal ID, or the System, for Deadlines, pollers,
 * and listeners acting on their own (AD-4).
 * @param accountId the acting account; {@code null} exactly when the actor is the System
 */
public record AuditActor(@Nullable UUID accountId) {

	private static final AuditActor SYSTEM = new AuditActor(null);

	public static AuditActor user(UUID accountId) {
		return new AuditActor(Objects.requireNonNull(accountId, "accountId"));
	}

	public static AuditActor system() {
		return SYSTEM;
	}

	public boolean isSystem() {
		return this.accountId == null;
	}

}
