package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A Crew change made an assigned member active on a running Job (FR-75, AD-41): {@code repo} grants them
 * access to the Working Repository as it did the first Crew (AD-11).
 * @param jobState the Job's state when the change took effect
 */
public record CrewMemberAdded(UUID eventId, UUID jobId, UUID rowId, UUID studentId, String jobState) implements DomainEvent {

}
