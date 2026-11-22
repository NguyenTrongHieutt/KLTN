package vn.vibefix.github.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubApps.App;
import vn.vibefix.github.SourcePushed;
import vn.vibefix.shared.IdGenerator;

/**
 * {@link SourcePushed}, published in the caller's transaction (Story 24.6), and only here: a webhook delivery
 * ({@link WebhookController}) becomes one only for a {@code push} the Source App sent whose payload names a repository
 * by its numeric ID, the ref it moved, the repository's default branch and the commit the ref points at now. Each
 * listed commit whose {@code id} is a commit SHA is carried, in the payload's order. Anything else publishes nothing.
 * Which push counts, and for which Job, is the listener's to decide.
 */
@Component
class SourcePushes {

	/** The event a push is delivered with. */
	static final String EVENT = "push";

	private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

	private final ApplicationEventPublisher events;

	private final IdGenerator ids;

	SourcePushes(ApplicationEventPublisher events, IdGenerator ids) {
		this.events = events;
		this.ids = ids;
	}

	/**
	 * A delivery seen for the first time, whose signature checked out with {@code app}'s secret: a Source App push
	 * publishes {@link SourcePushed}; anything else nothing.
	 * @return whether it published one
	 */
	boolean delivered(App app, String deliveryId, String event, JsonNode payload) {
		if (app != App.SOURCE || !EVENT.equals(event)) {
			return false;
		}
		JsonNode repository = payload.path("repository");
		long repositoryId = repository.path("id").isIntegralNumber() ? repository.path("id").asLong() : 0;
		String ref = text(payload.path("ref"));
		String defaultBranch = text(repository.path("default_branch"));
		String after = text(payload.path("after"));
		if (repositoryId <= 0 || ref.isBlank() || defaultBranch.isBlank() || !SHA.matcher(after).matches()) {
			return false;
		}
		List<String> commits = new ArrayList<>();
		for (JsonNode commit : payload.path("commits")) {
			String id = text(commit.path("id"));
			if (SHA.matcher(id).matches()) {
				commits.add(id);
			}
		}
		JsonNode installation = payload.path("installation").path("id");
		this.events.publishEvent(new SourcePushed(this.ids.newId(), deliveryId,
				installation.isIntegralNumber() ? installation.asLong() : null, repositoryId, ref, defaultBranch, after,
				commits));
		return true;
	}

	private static String text(JsonNode node) {
		return node.isString() ? node.asString() : "";
	}

}
