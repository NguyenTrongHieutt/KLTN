package vn.vibefix.github.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	/**
	 * One answer: its status, and its body as JSON (missing when it had none or was not JSON).
	 * @param link the {@code Link} header, which names the next page of a paged list
	 */
	record Answer(int status, JsonNode body, @Nullable String link) {

		Answer(int status, JsonNode body) {
			this(status, body, null);
		}

		/** The {@code cursor} of the next page, from {@code Link: <…&cursor=…>; rel="next"}; empty on the last page. */
		Optional<String> nextCursor() {
			if (this.link == null) {
				return Optional.empty();
			}
			for (String part : this.link.split(",")) {
				if (part.contains("rel=\"next\"")) {
					Matcher cursor = Pattern.compile("[?&]cursor=([^&>]+)").matcher(part);
					if (cursor.find()) {
						return Optional.of(cursor.group(1));
					}
				}
			}
			return Optional.empty();
		}

		/**
		 * The next page from {@code Link: <…>; rel="next"} as a path below {@code apiUrl}, for example
		 * {@code /repositories/7/collaborators?per_page=100&page=2}; empty on the last page.
		 */
		Optional<String> nextPath(URI apiUrl) {
			if (this.link == null) {
				return Optional.empty();
			}
			for (String part : this.link.split(",")) {
				if (part.contains("rel=\"next\"")) {
					Matcher url = Pattern.compile("<([^>]+)>").matcher(part);
					if (url.find()) {
						URI next = URI.create(url.group(1));
						String prefix = apiUrl.getRawPath() == null ? "" : apiUrl.getRawPath().replaceAll("/+$", "");
						String path = next.getRawPath();
						if (!prefix.isEmpty() && path.startsWith(prefix)) {
							path = path.substring(prefix.length());
						}
						return Optional.of(next.getRawQuery() == null ? path : path + "?" + next.getRawQuery());
					}
				}
			}
			return Optional.empty();
		}

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
		return sendAuthorized(method, path, "Bearer " + bearer, body);
	}

	/** As {@link #send}, with an {@code Authorization} value of the caller's own, for example HTTP Basic. */
	Answer sendAuthorized(String method, String path, String authorization, @Nullable Object body) {
		String base = this.apiUrl.toString().replaceAll("/+$", "");
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
			.timeout(Duration.ofSeconds(30))
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("Authorization", authorization);
		if (body == null) {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			request.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
		}
		try {
			HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			return new Answer(response.statusCode(), parse(response.body()), response.headers().firstValue("Link").orElse(null));
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
