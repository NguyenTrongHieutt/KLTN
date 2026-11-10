package vn.vibefix.github.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.GitHubApps.Registration;

/**
 * AD-10's registrations, which the daily configuration check (SG-18) and the {@code vibefix-dev} suite
 * (SG-20) compare with {@code GET /app}. GitHub leaves the global events ({@code meta},
 * {@code installation_target}, {@code security_advisory}) out of {@code GET /app} even when the App
 * subscribes to them (Spike S4, 2026-10-21), so they are accepted when they appear and never required;
 * the runbook checks {@code meta} on the App's settings page.
 */
final class AppExpectations {

	/** AD-10's Platform App: repository and organization permissions. */
	static final Map<String, String> PLATFORM_PERMISSIONS = Map.of("administration", "write", "contents", "write",
			"pull_requests", "write", "workflows", "write", "metadata", "read", "organization_administration", "read",
			"members", "read");

	/**
	 * What the Platform App holds besides when {@code vibefix.github.runner-repository} is set (Spike S6
	 * revision): Actions write, to dispatch the runner's handover copy and read its runs.
	 */
	static final Map<String, String> RUNNER_PERMISSIONS = Map.of("actions", "write");

	/** AD-10's Platform App events, as {@code GET /app} lists them. */
	static final Set<String> PLATFORM_EVENTS = Set.of("push", "pull_request", "repository", "member", "membership",
			"organization", "repository_ruleset");

	/** AD-10's Merge App: one Final Merge's permissions, and no event subscriptions. */
	static final Map<String, String> MERGE_PERMISSIONS = Map.of("contents", "write", "pull_requests", "write", "metadata",
			"read");

	/** Global events {@code GET /app} may list or leave out. */
	static final Set<String> GLOBAL_EVENTS = Set.of("meta", "installation_target", "security_advisory");

	private AppExpectations() {
	}

	/** AD-10's permissions with no runner repository configured. */
	static Map<String, String> permissions(App app) {
		return permissions(app, false);
	}

	/**
	 * AD-10's permissions.
	 * @param runner whether {@code vibefix.github.runner-repository} is set: the Platform App then holds
	 * {@link #RUNNER_PERMISSIONS} too
	 */
	static Map<String, String> permissions(App app, boolean runner) {
		if (app != App.PLATFORM) {
			return MERGE_PERMISSIONS;
		}
		if (!runner) {
			return PLATFORM_PERMISSIONS;
		}
		Map<String, String> withRunner = new HashMap<>(PLATFORM_PERMISSIONS);
		withRunner.putAll(RUNNER_PERMISSIONS);
		return Map.copyOf(withRunner);
	}

	static Set<String> events(App app) {
		return app == App.PLATFORM ? PLATFORM_EVENTS : Set.of();
	}

	/** Each way the registration differs from AD-10 with no runner repository, in plain words; empty when it matches. */
	static List<String> differences(App app, Registration registration) {
		return differences(app, registration, false);
	}

	/**
	 * Each way the registration differs from AD-10, in plain words; empty when it matches.
	 * @param runner whether {@code vibefix.github.runner-repository} is set
	 */
	static List<String> differences(App app, Registration registration, boolean runner) {
		List<String> differences = new ArrayList<>();
		Map<String, String> expected = permissions(app, runner);
		if (!registration.permissions().equals(expected)) {
			differences.add("permissions: " + new TreeMap<>(registration.permissions()) + " instead of "
					+ new TreeMap<>(expected));
		}
		Set<String> events = new HashSet<>(registration.events());
		events.removeAll(GLOBAL_EVENTS);
		if (!events.equals(events(app))) {
			differences.add("events: " + new TreeSet<>(events) + " instead of " + new TreeSet<>(events(app)));
		}
		return differences;
	}

}
