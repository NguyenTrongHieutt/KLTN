package vn.vibefix.github;

import java.util.UUID;

import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.shared.DomainEvent;

/**
 * A token the Actions exchange issued was revoked once its run completed, or after the run's timeout
 * (AD-44, FR-63): {@code admin} audits it.
 */
public record GitHubExchangeRevoked(UUID eventId, UUID correlationId, Operation operation) implements DomainEvent {
}
