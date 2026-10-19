package vn.vibefix.github.internal;

import java.net.URI;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vibefix.github.*} (AD-10, AD-25). The Apps' IDs and key files are optional until a story
 * needs them; a missing one refuses that App's tokens with a message naming the property.
 * @param apiUrl GitHub's REST API
 * @param gitUrl where repositories are cloned from and pushed to
 * @param organization the platform organization (AD-25)
 */
@ConfigurationProperties("vibefix.github")
record GitHubProperties(URI apiUrl, URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
		@Nullable AppProperties mergeApp) {

	/**
	 * @param id the App ID
	 * @param installationId the installation the platform uses (the Platform App's, on the organization)
	 * @param keyFile the App's private key, a PEM secret file (AD-22)
	 */
	record AppProperties(@Nullable Long id, @Nullable Long installationId, @Nullable Path keyFile) {
	}

	GitHubProperties {
		if (apiUrl == null) {
			apiUrl = URI.create("https://api.github.com");
		}
		if (gitUrl == null) {
			gitUrl = URI.create("https://github.com");
		}
	}

}
