package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A signed member's row was released as {@code LEFT} on a running Job (FR-59, FR-82, AD-41): the member left, or a lock
 * counted as leaving. {@code repo} revokes their write access, asks for the data-deletion confirmation and opens the
 * Customer's External Access to-dos (FR-21, FR-66), and {@code work} unassigns their open Work Items. A lock reaches the
 * same modules by {@code AccountLocked} too, so every handler is safe when that has already done its part.
 * @param studentId the member's account
 */
public record CrewMemberRemoved(UUID eventId, UUID jobId, UUID crewRowId, UUID studentId) implements DomainEvent {
}
