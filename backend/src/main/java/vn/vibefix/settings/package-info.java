/**
 * Layer 1 (AD-3): the rule values an Administrator may change (FR-61) and the school email domain
 * allowlist (FR-5), seeded with the PRD defaults. Values are read live when used; a module that must
 * keep a duration for a running Job copies it when the Job is published (AD-7). Writes arrive with
 * the Administrator rule-settings story, through {@code admin}, which records their audit entry.
 * <p>
 * Public API: {@link vn.vibefix.settings.Settings} and {@link vn.vibefix.settings.Rule}.
 */
@ApplicationModule(allowedDependencies = { "shared" })
package vn.vibefix.settings;

import org.springframework.modulith.ApplicationModule;
