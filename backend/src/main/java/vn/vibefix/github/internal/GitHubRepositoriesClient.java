package vn.vibefix.github.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import vn.vibefix.github.GitHubException;
import vn.vibefix.github.GitHubRepositories;

/**
 * {@link GitHubRepositories} on GitHub's REST API: the repository, then two small files of its
 * default branch that reveal submodules and Git LFS (AD-13). Three requests, all reads.
 */
@Component
class GitHubRepositoriesClient implements GitHubRepositories {

	private final GitHubHttp http;

	GitHubRepositoriesClient(GitHubProperties properties) {
		this.http = new GitHubHttp(properties.apiUrl());
	}

	@Override
	public Optional<RepositoryMetadata> read(String bearer, String owner, String name) {
		String path = "/repos/" + owner + "/" + name;
		GitHubHttp.Answer repository = this.http.send("GET", path, bearer, null);
		if (repository.status() == 404) {
			return Optional.empty();
		}
		if (repository.status() != 200) {
			throw new GitHubException(repository.status(), "GitHub refused the repository read: HTTP " + repository.status());
		}
		JsonNode body = repository.body();
		String branch = body.path("default_branch").asString("main");
		JsonNode permissions = body.path("permissions");
		JsonNode admin = permissions.path("admin");
		boolean submodules = exists(path + "/contents/.gitmodules?ref=" + branch, bearer);
		boolean lfs = file(path + "/contents/.gitattributes?ref=" + branch, bearer).contains("filter=lfs");
		String visibility = body.path("visibility").asString("");
		boolean publicRepository = !body.path("private").asBoolean(false)
				&& (visibility.isEmpty() || visibility.equals("public"));
		String parent = body.path("fork").asBoolean(false) ? body.path("parent").path("full_name").asString(null) : null;
		return Optional.of(new RepositoryMetadata(body.path("id").asLong(), body.path("full_name").asString(),
				body.path("html_url").asString(""), body.path("owner").path("id").asLong(),
				body.path("owner").path("type").asString(""), publicRepository, body.path("size").asLong(), parent, branch,
				admin.isBoolean() ? admin.booleanValue() : null, submodules, lfs));
	}

	private boolean exists(String path, String bearer) {
		GitHubHttp.Answer answer = this.http.send("GET", path, bearer, null);
		if (answer.status() == 200) {
			return true;
		}
		if (answer.status() == 404) {
			return false;
		}
		throw new GitHubException(answer.status(), "GitHub refused a file read: HTTP " + answer.status());
	}

	/** A small file's text, or empty when it does not exist. */
	private String file(String path, String bearer) {
		GitHubHttp.Answer answer = this.http.send("GET", path, bearer, null);
		if (answer.status() == 404) {
			return "";
		}
		if (answer.status() != 200) {
			throw new GitHubException(answer.status(), "GitHub refused a file read: HTTP " + answer.status());
		}
		String content = answer.body().path("content").asString("");
		try {
			return new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return "";
		}
	}

}
