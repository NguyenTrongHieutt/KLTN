package vn.vibefix.github;

import java.util.UUID;

import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.shared.DomainEvent;

/**
 * vibefix restarted while a token the Actions exchange issued was still out (AD-44, AD-22): tokens live only
 * in memory, so nothing can revoke it any more, and GitHub expires it within the hour. Published once per
 * correlation; {@code admin} audits it and alerts every Administrator.
 */
public record GitHubExchangeRevokeUnknown(UUID eventId, UUID correlationId, Operation operation)
		implements DomainEvent {
}
