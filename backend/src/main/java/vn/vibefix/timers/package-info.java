/**
 * Layer 1 (AD-3): Deadlines instead of in-memory timers (AD-7). Every time-based rule is a row in
 * {@code timers.deadline}: its kind, the subject and the generation it serves, an optional Job, and its
 * due time, or the time it has left while its Job is frozen. A poller claims due rows every 30 s with
 * {@code SKIP LOCKED} and calls the kind's {@link vn.vibefix.timers.DeadlineHandler}, which an owning
 * upper module implements (AD-3 port) and which answers a {@link vn.vibefix.timers.DeadlineOutcome}.
 * Cancellation is lazy: a handler whose generation no longer matches answers {@code OBSOLETE}.
 * <p>
 * Public API: {@link vn.vibefix.timers.Deadlines}, {@link vn.vibefix.timers.DeadlineRequest},
 * {@link vn.vibefix.timers.DeadlineKind}, {@link vn.vibefix.timers.DeadlineHandler},
 * {@link vn.vibefix.timers.DueDeadline} and {@link vn.vibefix.timers.DeadlineOutcome}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.timers;

import org.springframework.modulith.ApplicationModule;
