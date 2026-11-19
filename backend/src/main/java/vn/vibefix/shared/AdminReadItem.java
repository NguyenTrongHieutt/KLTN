package vn.vibefix.shared;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What one Administrator read grant covers in one {@link AdminReadScope} (AD-20): all of it or a single object. The
 * named scopes below are the only definition of what each case kind may read (AD-33).
 * <p>
 * A third breadth, the content involving one account, went with the Work Item histories it was made for: a Split
 * Resolution's grant covered each party's, and an escalated Team Issue's the raiser's, and Story 24.2 retired both
 * with the Board. Nothing produces an account-scoped item now, so nothing checks one -- a live-looking breadth that
 * no caller can produce is how a future grant silently gets the wrong reach. Stored rows keep their
 * {@code "accountId": ""} field, which the readers no longer look at.
 * @param scope the kind of content
 * @param objectId the single object covered, or {@code null} for all of the scope on the Job
 */
public record AdminReadItem(AdminReadScope scope, @Nullable UUID objectId) {

	public AdminReadItem {
		Objects.requireNonNull(scope, "scope");
	}

	/** Everything of {@code scope} on the Job. */
	public static AdminReadItem all(AdminReadScope scope) {
		return new AdminReadItem(scope, null);
	}

	/** The single object {@code objectId} of {@code scope}. */
	public static AdminReadItem one(AdminReadScope scope, UUID objectId) {
		return new AdminReadItem(scope, Objects.requireNonNull(objectId, "objectId"));
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

	/** Whether this item covers the object {@code objectId} of {@code scope}. */
	public boolean covers(AdminReadScope scope, UUID objectId) {
		if (this.scope != scope) {
			return false;
		}
		return this.objectId == null || this.objectId.equals(objectId);
	}

}
