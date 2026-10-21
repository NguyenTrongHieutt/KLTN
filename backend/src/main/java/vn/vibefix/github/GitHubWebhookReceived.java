package vn.vibefix.github;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.shared.DomainEvent;

/**
 * A GitHub webhook whose signature checked out with its App's secret, seen once (AD-12, SG-18). It is a
 * hint, never a fact: a listener acts only on a known installation and a known platform or Source
 * Repository, except a Merge App {@code installation} or {@code installation_repositories} event, which
 * AD-10's checks resolve whatever its installation, and it re-reads state from GitHub before acting.
 * @param deliveryId {@code X-GitHub-Delivery}
 * @param event {@code X-GitHub-Event}, for example {@code push}
 * @param action the payload's {@code action}, when it has one
 * @param repositoryIds every repository the payload names: {@code repository}, {@code repositories},
 * {@code repositories_added}, and {@code repositories_removed}
 * @param senderId the numeric ID of the account that caused it
 * @param requesterId the numeric ID of the account that asked for an installation, when someone else approved it
 */
public record GitHubWebhookReceived(UUID eventId, String deliveryId, App app, String event, @Nullable String action,
		@Nullable Long installationId, List<Long> repositoryIds, @Nullable Long senderId, @Nullable Long requesterId)
		implements DomainEvent {

	public GitHubWebhookReceived {
		repositoryIds = List.copyOf(repositoryIds);
	}

}
