package vn.vibefix.shared;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What one Administrator read grant covers in one {@link AdminReadScope} (AD-20): all of it, the
 * content involving one account, or a single object. The named scopes below are the only
 * definition of what each case kind may read (AD-33).
 * @param scope the kind of content
 * @param objectId the single object covered, or {@code null}
 * @param accountId the account whose content is covered, or {@code null}; at most one of the two
 * is set
 */
public record AdminReadItem(AdminReadScope scope, @Nullable UUID objectId, @Nullable UUID accountId) {

	public AdminReadItem {
		Objects.requireNonNull(scope, "scope");
		if (objectId != null && accountId != null) {
			throw new IllegalArgumentException("An item covers one object or one account's content, not both");
		}
	}

	/** Everything of {@code scope} on the Job. */
	public static AdminReadItem all(AdminReadScope scope) {
		return new AdminReadItem(scope, null, null);
	}

	/** The single object {@code objectId} of {@code scope}. */
	public static AdminReadItem one(AdminReadScope scope, UUID objectId) {
		return new AdminReadItem(scope, Objects.requireNonNull(objectId, "objectId"), null);
	}

	/** The content of {@code scope} that involves {@code accountId}, as the serving module defines it. */
	public static AdminReadItem involving(AdminReadScope scope, UUID accountId) {
		return new AdminReadItem(scope, null, Objects.requireNonNull(accountId, "accountId"));
	}

	/** A Dispute's grant: the Job Chat; never the Crew Chat or split versions. */
	public static Set<AdminReadItem> forDispute() {
		return Set.of(all(AdminReadScope.JOB_CHAT));
	}

	/**
	 * An escalated Team Issue's grant: the Crew Chat; the Rating itself is read through {@code reputation}.
	 */
	public static Set<AdminReadItem> forEscalatedTeamIssue() {
		return Set.of(all(AdminReadScope.CREW_CHAT));
	}

	/**
	 * A Split Resolution's grant (AD-20, AD-40): the Milestone's split versions and the Crew Chat; never the Job
	 * Chat.
	 */
	public static Set<AdminReadItem> forSplitResolution(UUID splitId) {
		return Set.of(one(AdminReadScope.SPLIT_VERSIONS, splitId), all(AdminReadScope.CREW_CHAT));
	}

	/** An Abuse Report's grant: the single item it names. */
	public static Set<AdminReadItem> forAbuseReport(AdminReadScope scope, UUID reportedItemId) {
		return Set.of(one(scope, reportedItemId));
	}

	/**
	 * Whether this item covers the object {@code objectId} of {@code scope}, whose content involves
	 * {@code involvedAccountIds} (for a Chat message: its author).
	 */
	public boolean covers(AdminReadScope scope, UUID objectId, Collection<UUID> involvedAccountIds) {
		if (this.scope != scope) {
			return false;
		}
		if (this.objectId != null) {
			return this.objectId.equals(objectId);
		}
		if (this.accountId != null) {
			return involvedAccountIds.contains(this.accountId);
		}
		return true;
	}

}
