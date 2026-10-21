package vn.vibefix.operations;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * An External Operation ended {@link OperationStatus#FAILED} after its last attempt (AD-6, FR-64): it waits
 * in ADM-7's queue, and {@code admin} alerts every Administrator ({@code operations.operation_failed}).
 * @param type the operation's type, such as {@code notification.email}
 * @param errorCode the last attempt's error code; never a message, which may name a person
 */
public record OperationFailed(UUID eventId, UUID operationId, String type, String errorCode) implements DomainEvent {
}
