package vn.vibefix.github.internal;

import java.util.ArrayList;
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

	/**
	 * AD-10's Platform App: repository and organization permissions. Actions write dispatches the runner's
	 * workflow (AD-46), and Workflows write is issued only in exchanged tokens (AD-44); both are always held
	 * since the runner is required (2026-11-12).
	 */
	static final Map<String, String> PLATFORM_PERMISSIONS = Map.of("administration", "write", "contents", "write",
			"pull_requests", "write", "workflows", "write", "actions", "write", "metadata", "read",
			"organization_administration", "read", "members", "read");

	/**
	 * AD-10's Platform App events, as {@code GET /app} lists them. {@code push} and {@code repository_ruleset}
	 * were dropped on 2026-11-12: heads are compared only at acceptance, and private repositories on GitHub
	 * Free take no ruleset (AD-12, R9).
	 */
	static final Set<String> PLATFORM_EVENTS = Set.of("pull_request", "repository", "member", "membership",
			"organization");

	/** AD-10's Merge App: one Final Merge's permissions, and no event subscriptions. */
	static final Map<String, String> MERGE_PERMISSIONS = Map.of("contents", "write", "pull_requests", "write", "metadata",
			"read");

	/**
	 * AD-10's Source App (Story 23.5): it reads the Source Repository's code and metadata and dispatches
	 * {@code vibefix.yml}, and it subscribes to no event and has no webhook.
	 */
	static final Map<String, String> SOURCE_PERMISSIONS = Map.of("contents", "read", "metadata", "read", "actions",
			"write");

	/** Global events {@code GET /app} may list or leave out. */
	static final Set<String> GLOBAL_EVENTS = Set.of("meta", "installation_target", "security_advisory");

	private AppExpectations() {
	}

	/** AD-10's permissions. */
	static Map<String, String> permissions(App app) {
		return switch (app) {
			case PLATFORM -> PLATFORM_PERMISSIONS;
			case MERGE -> MERGE_PERMISSIONS;
			case SOURCE -> SOURCE_PERMISSIONS;
		};
	}

	static Set<String> events(App app) {
		return switch (app) {
			case PLATFORM -> PLATFORM_EVENTS;
			case MERGE, SOURCE -> Set.of();
		};
	}

	/**
	 * Whether AD-10 gives the App a webhook: the Platform App's and the Merge App's reach {@code POST /webhooks}; the
	 * Source App has none, so the daily check and the redelivery leave its webhook alone.
	 */
	static boolean webhook(App app) {
		return switch (app) {
			case PLATFORM, MERGE -> true;
			case SOURCE -> false;
		};
	}

	/** Each way the registration differs from AD-10, in plain words; empty when it matches. */
	static List<String> differences(App app, Registration registration) {
		List<String> differences = new ArrayList<>();
		Map<String, String> expected = permissions(app);
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
