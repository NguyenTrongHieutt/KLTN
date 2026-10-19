package vn.vibefix.github.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * GitHub's REST API with one bearer credential per call (a JWT or an installation token), sent to
 * the configured API host only: redirects are not followed, so a moved repository shows its 301.
 */
final class GitHubHttp {

	static final JsonMapper JSON = JsonMapper.builder().build();

	/** One answer: its status, and its body as JSON (missing when it had none or was not JSON). */
	record Answer(int status, JsonNode body) {

		boolean ok() {
			return this.status >= 200 && this.status < 300;
		}

	}

	private final URI apiUrl;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	GitHubHttp(URI apiUrl) {
		this.apiUrl = apiUrl;
	}

	/**
	 * @param path an API path, for example {@code /app/installations/1/access_tokens}
	 * @param body a JSON body, or none
	 * @return the answer; status 0 when GitHub could not be reached
	 */
	Answer send(String method, String path, String bearer, @Nullable Object body) {
		String base = this.apiUrl.toString().replaceAll("/+$", "");
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
			.timeout(Duration.ofSeconds(30))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("Authorization", "Bearer " + bearer);
		if (body == null) {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			request.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
		}
		try {
			HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			return new Answer(response.statusCode(), parse(response.body()));
		}
		catch (IOException ex) {
			return new Answer(0, MissingNode.getInstance());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Answer(0, MissingNode.getInstance());
		}
	}

	private static JsonNode parse(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return MissingNode.getInstance();
		}
		try {
			return JSON.readTree(body);
		}
		catch (RuntimeException ex) {
			return MissingNode.getInstance();
		}
	}

}
