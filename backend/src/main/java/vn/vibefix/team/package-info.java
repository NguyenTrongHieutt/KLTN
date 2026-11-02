/**
 * Layer 3 (AD-3, AD-37): the shell of the retired Mentor Teams. It holds no class, API, endpoint, listener, or
 * Deadline: only this file and {@code db/migration/team/}, since Flyway's history and {@code ModuleLayersTest}'s layer
 * table name the module. Nothing depends on it, and it depends on nothing. A Job's Crew comes from Crew Applications
 * (AD-3, AD-41).
 */
@ApplicationModule(allowedDependencies = {})
package vn.vibefix.team;

import org.springframework.modulith.ApplicationModule;
