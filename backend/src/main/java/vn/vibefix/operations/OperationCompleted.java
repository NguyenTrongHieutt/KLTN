package vn.vibefix.operations;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import vn.vibefix.shared.DomainEvent;

/**
 * An External Operation completed (AD-6, AD-9): published in the completion transaction, with its
 * status and audit entry. The initiator listens for its own types and reads the result with
 * {@link ExternalOperations#find}.
 */
public record OperationCompleted(UUID eventId, UUID operationId, String key, String type, OperationStatus status,
		@Nullable String errorCode) implements DomainEvent {

}
