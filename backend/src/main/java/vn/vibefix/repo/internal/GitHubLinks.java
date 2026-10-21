package vn.vibefix.repo.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Links to the platform organization's repositories on GitHub's website, for the step tracker. */
@Component
class GitHubLinks {

	private final String web;

	GitHubLinks(@Value("${vibefix.github.web-url:https://github.com}") String web) {
		this.web = web.endsWith("/") ? web.substring(0, web.length() - 1) : web;
	}

	/** Where an invitee accepts the repository's invitation. */
	String invitations(String fullName) {
		return repository(fullName) + "/invitations";
	}

	String repository(String fullName) {
		return this.web + "/" + fullName;
	}

}
