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
	 * Free take no ruleset (AD-12, R9). {@code workflow_run} joined in Story 24.5a: the runner's handover copy
	 * resolves on its completion instead of being read on an interval.
	 */
	static final Set<String> PLATFORM_EVENTS = Set.of("pull_request", "repository", "member", "membership",
			"organization", "workflow_run");

	/**
	 * AD-10's Source App (Story 23.5): it reads the Source Repository's code and metadata and dispatches
	 * {@code vibefix.yml}. Its permissions stay as they are.
	 */
	static final Map<String, String> SOURCE_PERMISSIONS = Map.of("contents", "read", "metadata", "read", "actions",
			"write");

	/**
	 * AD-10's Source App events: {@code workflow_run} since Story 24.5a, so the J7 copy and the Final Merge resolve on
	 * their run's completion, and since Story 24.6 {@code push} and {@code repository}, so a Source Repository that
	 * drifts during a Job is warned of within seconds (FR-20).
	 */
	static final Set<String> SOURCE_EVENTS = Set.of("workflow_run", "push", "repository");

	/**
	 * The Source App's events {@code GET /app} may list or leave out (Story 24.6): GitHub sends {@code installation} and
	 * {@code installation_repositories} to every App, subscribed or not, and the Source drift reads both.
	 */
	static final Set<String> SOURCE_EITHER_WAY = Set.of("installation", "installation_repositories");

	/** Global events {@code GET /app} may list or leave out. */
	static final Set<String> GLOBAL_EVENTS = Set.of("meta", "installation_target", "security_advisory");

	private AppExpectations() {
	}

	/** AD-10's permissions. */
	static Map<String, String> permissions(App app) {
		return switch (app) {
			case PLATFORM -> PLATFORM_PERMISSIONS;
			case SOURCE -> SOURCE_PERMISSIONS;
		};
	}

	/** AD-10's events. */
	static Set<String> events(App app) {
		return switch (app) {
			case PLATFORM -> PLATFORM_EVENTS;
			case SOURCE -> SOURCE_EVENTS;
		};
	}

	/** The App's events, beside the global ones, that {@code GET /app} may list or leave out. */
	static Set<String> eitherWay(App app) {
		return switch (app) {
			case PLATFORM -> Set.of();
			case SOURCE -> SOURCE_EITHER_WAY;
		};
	}

	/**
	 * Whether AD-10 gives the App a webhook: both Apps' reach {@code POST /webhooks} since Story 24.5a, each signed with
	 * its own secret, so the daily check compares each one's hook and the redelivery reads each one's deliveries.
	 */
	static boolean webhook(App app) {
		return switch (app) {
			case PLATFORM -> true;
			case SOURCE -> true;
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
		events.removeAll(eitherWay(app));
		if (!events.equals(events(app))) {
			differences.add("events: " + new TreeSet<>(events) + " instead of " + new TreeSet<>(events(app)));
		}
		return differences;
	}

}
