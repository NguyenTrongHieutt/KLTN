package vn.vibefix.github;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.shared.DomainEvent;

/**
 * The daily configuration check found an App unlike AD-10 (SG-18): its permissions, events, or webhook.
 * {@code admin} alerts every Administrator, once a day per App.
 * @param differences each difference in plain words, for example {@code webhook URL: https://…}
 */
public record GitHubConfigurationDrift(UUID eventId, App app, LocalDate day, List<String> differences)
		implements DomainEvent {

	public GitHubConfigurationDrift {
		differences = List.copyOf(differences);
	}

}
