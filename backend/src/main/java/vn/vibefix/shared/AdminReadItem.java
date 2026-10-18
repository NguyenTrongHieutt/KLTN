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

	/**
	 * A Dispute's grant: the Job Chat, Work Items with history, notes, the Attachment list, and
	 * Pages; never the Crew Chat or Crew Terms.
	 */
	public static Set<AdminReadItem> forDispute() {
		return Set.of(all(AdminReadScope.JOB_CHAT), all(AdminReadScope.WORK_ITEMS),
				all(AdminReadScope.WORK_ITEM_NOTES), all(AdminReadScope.ATTACHMENTS), all(AdminReadScope.PAGES));
	}

	/**
	 * An escalated Team Issue's grant: the Crew Chat, the raiser's Work Item histories, and Crew
	 * Terms versions.
	 */
	public static Set<AdminReadItem> forEscalatedTeamIssue(UUID raiserAccountId) {
		return Set.of(all(AdminReadScope.CREW_CHAT), involving(AdminReadScope.WORK_ITEMS, raiserAccountId),
				all(AdminReadScope.CREW_TERMS));
	}

	/** An Abuse Report's grant: the single item it names. */
	public static Set<AdminReadItem> forAbuseReport(AdminReadScope scope, UUID reportedItemId) {
		return Set.of(one(scope, reportedItemId));
	}

	/**
	 * Whether this item covers the object {@code objectId} of {@code scope}, whose content involves
	 * {@code involvedAccountIds} (for a Work Item: its assignees and history authors).
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
