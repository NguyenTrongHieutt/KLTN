package vn.vibefix.github.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.sun.net.httpserver.HttpExchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What only Spike S4's probe still asks of GitHub, which the platform retired with its public repositories
 * (Story 23.3): rulesets and forks inside the organization. As GitHub does (Spike S4, 2026-10-21), it shows a
 * ruleset's {@code bypass_actors} only to a token with Administration write, refuses a fork to a token narrowed
 * by {@code repository_ids}, and shows a fork's commits only after one poll, as GitHub forks asynchronously. It
 * answers through {@link GitHubStub#extra}, so the stub itself keeps none of it.
 */
final class SpikeS4Routes implements GitHubStub.Route {

	private static final Pattern RULESETS = Pattern.compile("/repos/" + GitHubStub.ORGANIZATION + "/([^/]+)/rulesets");

	private static final Pattern RULESET = Pattern.compile("/repos/" + GitHubStub.ORGANIZATION + "/([^/]+)/rulesets/(\\d+)");

	private static final Pattern FORKS = Pattern.compile("/repos/" + GitHubStub.ORGANIZATION + "/([^/]+)/forks");

	private static final Pattern COMMITS = Pattern.compile("/repos/" + GitHubStub.ORGANIZATION + "/([^/]+)/commits");

	private final GitHubStub github;

	private final AtomicLong ids = new AtomicLong(900_000);

	private final Map<String, List<JsonNode>> rulesets = Collections.synchronizedMap(new LinkedHashMap<>());

	private final Map<String, Integer> forkPolls = Collections.synchronizedMap(new LinkedHashMap<>());

	SpikeS4Routes(GitHubStub github) {
		this.github = github;
	}

	@Override
	public boolean answer(HttpExchange exchange, GitHubStub.Seen request, @Nullable String query) throws Exception {
		String path = request.path();
		Matcher matcher;
		if ((matcher = RULESETS.matcher(path)).matches()) {
			List<JsonNode> list = this.rulesets.computeIfAbsent(matcher.group(1), name -> Collections.synchronizedList(new ArrayList<>()));
			if (request.method().equals("POST")) {
				ObjectNode body = ((ObjectNode) request.body()).deepCopy();
				body.put("id", this.ids.incrementAndGet());
				list.add(body);
				GitHubStub.send(exchange, 201, GitHubHttp.JSON.writeValueAsString(body));
			}
			else {
				GitHubStub.send(exchange, 200, GitHubHttp.JSON.writeValueAsString(List.copyOf(list)
					.stream()
					.map(ruleset -> Map.of("id", ruleset.path("id").asLong(), "name", ruleset.path("name").asString()))
					.toList()));
			}
			return true;
		}
		if ((matcher = RULESET.matcher(path)).matches()) {
			String id = matcher.group(2);
			JsonNode ruleset = List.copyOf(this.rulesets.getOrDefault(matcher.group(1), List.of()))
				.stream()
				.filter(found -> found.path("id").asString().equals(id))
				.findFirst()
				.orElse(null);
			if (ruleset == null) {
				GitHubStub.send(exchange, 404, "{\"message\":\"Not Found\"}");
				return true;
			}
			ObjectNode shown = (ObjectNode) ruleset.deepCopy();
			if (!this.github.grant(exchange).path("permissions").path("administration").asString("").equals("write")) {
				shown.remove("bypass_actors");
			}
			GitHubStub.send(exchange, 200, GitHubHttp.JSON.writeValueAsString(shown));
			return true;
		}
		if ((matcher = FORKS.matcher(path)).matches()) {
			if (this.github.grant(exchange).has("repository_ids")) {
				GitHubStub.send(exchange, 403, "{\"message\":\"Resource not accessible by integration\"}");
				return true;
			}
			String forkName = request.body().path("name").asString();
			long id = this.github.platformRepository(forkName, GitHubStub.ORGANIZATION, matcher.group(1));
			GitHubStub.send(exchange, 202, "{\"id\":" + id + ",\"name\":\"" + forkName + "\"}");
			return true;
		}
		if ((matcher = COMMITS.matcher(path)).matches()) {
			int polls = this.forkPolls.merge(matcher.group(1), 1, Integer::sum);
			GitHubStub.send(exchange, polls < 2 ? 409 : 200,
					polls < 2 ? "{\"message\":\"Git Repository is empty.\"}" : "[{\"sha\":\"abc\"}]");
			return true;
		}
		return false;
	}

}
