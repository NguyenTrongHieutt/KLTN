package vn.vibefix.github;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;

/**
 * A {@code push} to a repository a Customer's Source App is installed on, as the Source App delivered it (AD-12, FR-20,
 * Story 24.6), published beside {@link GitHubWebhookReceived} for the same delivery. Like it, it is a hint: a listener
 * matches the repository by its permanent ID and reads state from GitHub through the Source App before it acts on
 * anything but the push itself.
 * @param deliveryId {@code X-GitHub-Delivery}
 * @param installationId the Source App installation the delivery is for, when the payload names one
 * @param repositoryId the repository's permanent numeric ID
 * @param ref the ref the push moved, such as {@code refs/heads/main}
 * @param defaultBranch the repository's default branch, as the payload names it
 * @param after the commit the ref points at after the push, 40 hex; 40 zeros when the push deleted the ref
 * @param commits the IDs of the commits the push brought, in the payload's order, oldest first
 */
public record SourcePushed(UUID eventId, String deliveryId, @Nullable Long installationId, long repositoryId, String ref,
		String defaultBranch, String after, List<String> commits) implements DomainEvent {

	/** The {@code after} of a push that deleted its ref. */
	public static final String DELETED = "0".repeat(40);

	public SourcePushed {
		commits = List.copyOf(commits);
	}

	/** Whether the push moved the repository's default branch to a commit, rather than another ref or a deletion. */
	public boolean toDefaultBranch() {
		return ("refs/heads/" + this.defaultBranch).equals(this.ref) && !DELETED.equals(this.after);
	}

}
