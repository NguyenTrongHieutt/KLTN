package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Crew change took a member off a running Job (FR-75, AD-41): their row is released as
 * {@code REMOVED}, they keep what was released, and {@code repo} opens their data-deletion duty (FR-66).
 */
public record CrewMemberRemoved(UUID eventId, UUID jobId, UUID crewRowId, UUID studentId) implements DomainEvent {
}
