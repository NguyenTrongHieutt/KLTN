package vn.vibefix.github;

import java.util.UUID;

import vn.vibefix.shared.DomainEvent;

/**
 * Something new is known about the run of an exchange correlation (AD-44, AD-46, Story 24.5a), so the operation that
 * dispatched it reads the run again: GitHub's {@code workflow_run} {@code completed} delivery from the App that
 * dispatched it, on the correlation's installation, for the correlation's own run while the correlation is open or
 * exchanged (its token since revoked included); an accepted path report of a {@code handover-copy} run; or the
 * exchange's refusal of the run's ID token. It is a hint, never a fact: the operation re-reads the run and the
 * repositories through the API before it acts. Each one is also recorded on the correlation
 * ({@link ActionsRuns#updated}).
 * @param correlationId the correlation whose run the hint is about
 */
public record ActionsRunUpdated(UUID eventId, UUID correlationId) implements DomainEvent {
}
