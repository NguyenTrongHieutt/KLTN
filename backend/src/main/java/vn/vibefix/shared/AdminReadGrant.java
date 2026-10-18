package vn.vibefix.shared;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One Administrator read grant as a module holding Job content stores it (AD-20): pushed down by
 * the owning case module through {@link AdminReadGrants}, and checked by the serving module before
 * every read under it.
 * @param grantId the grant
 * @param adminId the assigned Administrator
 * @param jobId the Job whose content it covers
 * @param items what it covers
 * @param reasonId the Dispute, escalated Team Issue, or Abuse Report it serves
 * @param endsAt the instant from which it no longer allows reads; {@code null} while it is open
 */
public record AdminReadGrant(UUID grantId, UUID adminId, UUID jobId, Set<AdminReadItem> items, UUID reasonId,
		@Nullable Instant endsAt) {

	public AdminReadGrant {
		Objects.requireNonNull(grantId, "grantId");
		Objects.requireNonNull(adminId, "adminId");
		Objects.requireNonNull(jobId, "jobId");
		Objects.requireNonNull(reasonId, "reasonId");
		items = Set.copyOf(items);
		if (items.isEmpty()) {
			throw new IllegalArgumentException("A grant covers at least one item");
		}
	}

	public static AdminReadGrant open(UUID grantId, UUID adminId, UUID jobId, Set<AdminReadItem> items,
			UUID reasonId) {
		return new AdminReadGrant(grantId, adminId, jobId, items, reasonId, null);
	}

	/**
	 * The grant ending at {@code at}: the current time on reassignment, the decision time plus 30
	 * days on the decision. An end never extends a grant, so the earlier end wins.
	 */
	public AdminReadGrant endingAt(Instant at) {
		Objects.requireNonNull(at, "at");
		Instant end = this.endsAt == null || at.isBefore(this.endsAt) ? at : this.endsAt;
		return new AdminReadGrant(this.grantId, this.adminId, this.jobId, this.items, this.reasonId, end);
	}

	/**
	 * Whether {@code adminId} may read, at {@code at}, the object {@code objectId} of {@code scope}
	 * on {@code jobId} that involves {@code involvedAccountIds}. A serving module answers
	 * {@code NOT_FOUND} when no grant permits the read, and writes an audit entry for every read a
	 * grant permits (AD-8).
	 */
	public boolean permits(UUID adminId, UUID jobId, AdminReadScope scope, UUID objectId,
			Collection<UUID> involvedAccountIds, Instant at) {
		return this.adminId.equals(adminId) && this.jobId.equals(jobId)
				&& (this.endsAt == null || at.isBefore(this.endsAt))
				&& this.items.stream().anyMatch(item -> item.covers(scope, objectId, involvedAccountIds));
	}

}
