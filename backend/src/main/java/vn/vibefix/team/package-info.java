/**
 * Layer 3 (AD-3, AD-37): Mentor Teams. Each approved Mentor has one Team, created when
 * {@code identity} publishes {@code MentorApproved}; a Student is in one Team at a time, joining by the
 * Mentor's invitation or their own join request, both lapsing after 14 days through a {@code timers}
 * Deadline. A Mentor who is revoked or locked closes their Team and releases its Students. Profiles
 * are read only through {@code identity}'s public search API (AD-36); {@code team} stores IDs.
 * <p>
 * Public API: {@link vn.vibefix.team.Teams}, for display only. The HTTP endpoints, the listeners,
 * and the Deadline are wiring in {@code team.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "timers", "notification" })
package vn.vibefix.team;

import org.springframework.modulith.ApplicationModule;
