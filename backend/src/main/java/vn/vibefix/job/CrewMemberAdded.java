package vn.vibefix.job;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * A picked person signed and joined the Crew (FR-75, FR-81, AD-41, Story 21.5), in Agreement, Starting or Active:
 * {@code repo} grants a member who joins a Code Job after its Setup access to the Working Repository (FR-18, AD-11),
 * and {@code matching} lapses the person's pending Crew Applications on other Jobs and closes the Mentor's
 * open-recruitment to-do (A-16).
 * @param studentId the member's account
 * @param jobState the Job's state when the member joined
 */
public record CrewMemberAdded(UUID eventId, UUID jobId, UUID rowId, UUID studentId, String jobState) implements DomainEvent {

}
