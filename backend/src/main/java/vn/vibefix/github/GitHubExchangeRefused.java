package vn.vibefix.github;

import java.util.UUID;

import vn.vibefix.github.ActionsRuns.Operation;
import vn.vibefix.shared.DomainEvent;

/**
 * The Actions token exchange refused a verified ID token that named an open or exchanged correlation (AD-44,
 * FR-63): {@code admin} audits it and alerts every Administrator. Refusals before verification are only
 * counted, so no event names them.
 * @param reason one of the checks from {@link ExchangeReason#REPLAY} on
 */
public record GitHubExchangeRefused(UUID eventId, UUID correlationId, Operation operation, ExchangeReason reason)
		implements DomainEvent {
}
