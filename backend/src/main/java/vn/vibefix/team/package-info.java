/**
 * Layer 3 (AD-3, AD-37): Mentor Teams, until Story 21.7 retires them. A Mentor has at most one Team,
 * created on demand by their first invitation or a Student's join request, since nobody is approved any
 * more (Story 19.7); a Student is in one Team at a time, joining by the Mentor's invitation or their own
 * join request, both lapsing after 14 days through a {@code timers} Deadline. A lock acts on no Team since
 * Story 20.4. Profiles are read only through {@code identity}'s public
 * search API (AD-36); {@code team} stores IDs.
 * <p>
 * Public API: {@link vn.vibefix.team.Teams}, for display only. The HTTP endpoints, the listeners,
 * and the Deadline are wiring in {@code team.internal}.
 */
@ApplicationModule(allowedDependencies = { "shared", "audit", "identity", "timers", "notification" })
package vn.vibefix.team;

import org.springframework.modulith.ApplicationModule;
