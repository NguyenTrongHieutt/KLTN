package vn.vibefix.github;

import java.util.UUID;

import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.shared.DomainEvent;

/**
 * The Actions token exchange issued a run its token (AD-44, FR-63): {@code admin} audits it. The event names
 * the correlation and the run, never the token.
 */
public record GitHubExchangeIssued(UUID eventId, UUID correlationId, Operation operation, long runId)
		implements DomainEvent {
}
