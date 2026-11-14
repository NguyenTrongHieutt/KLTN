package vn.vibefix.github;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.shared.DomainEvent;

/**
 * A GitHub webhook whose signature checked out with its App's secret, seen once (AD-12, SG-18). Only the
 * Platform App has a webhook (Story 23.9b). It is a hint, never a fact: a listener acts only on a known
 * installation and a known platform repository, and it re-reads state from GitHub before acting.
 * @param deliveryId {@code X-GitHub-Delivery}
 * @param event {@code X-GitHub-Event}, for example {@code push}
 * @param action the payload's {@code action}, when it has one
 * @param repositoryIds the repository the payload's {@code repository} names, if any; the Platform App
 * subscribes to no event that names more than one
 * @param senderId the numeric ID of the account that caused it
 * @param senderLogin the login of the account that caused it, compared with grants and the operator account
 */
public record GitHubWebhookReceived(UUID eventId, String deliveryId, App app, String event, @Nullable String action,
		@Nullable Long installationId, List<Long> repositoryIds, @Nullable Long senderId, @Nullable String senderLogin)
		implements DomainEvent {

	public GitHubWebhookReceived {
		repositoryIds = List.copyOf(repositoryIds);
	}

}
