package vn.vibefix.identity;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A newly recorded person-link signal of {@code accountId} is one {@code otherAccountId} already has
 * (AD-19): {@code job} alerts Administrators for each running Job both take part in.
 * @param kind {@code EMAIL} or {@code LINKEDIN}; never the value or its HMAC
 */
public record PersonSignalMatched(UUID eventId, UUID accountId, UUID otherAccountId, String kind)
		implements DomainEvent {
}
