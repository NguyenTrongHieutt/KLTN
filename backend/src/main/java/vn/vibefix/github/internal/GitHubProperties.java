package vn.vibefix.github.internal;

import java.net.URI;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code vibefix.github.*} (AD-10, AD-12, AD-25). The Apps' IDs, key files, and webhook secrets are
 * optional until a story needs them; a missing one refuses that App's tokens or webhooks with a message
 * naming the property.
 * @param apiUrl GitHub's REST API
 * @param gitUrl where repositories are cloned from and pushed to
 * @param organization the platform organization (AD-25)
 * @param webhookUrl where both Apps' webhooks must point ({@code https://…/webhooks}); the daily
 * configuration check compares it when set
 */
@ConfigurationProperties("vibefix.github")
record GitHubProperties(URI apiUrl, URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
		@Nullable AppProperties mergeApp, @Nullable URI webhookUrl) {

	/**
	 * @param id the App ID
	 * @param installationId the installation the platform uses (the Platform App's, on the organization)
	 * @param keyFile the App's private key, a PEM secret file (AD-22)
	 * @param webhookSecretFile the App's webhook secret, a secret file (AD-12, AD-22)
	 */
	record AppProperties(@Nullable Long id, @Nullable Long installationId, @Nullable Path keyFile,
			@Nullable Path webhookSecretFile) {

		@ConstructorBinding
		AppProperties {
		}

		AppProperties(@Nullable Long id, @Nullable Long installationId, @Nullable Path keyFile) {
			this(id, installationId, keyFile, null);
		}

	}

	@ConstructorBinding
	GitHubProperties {
		if (apiUrl == null) {
			apiUrl = URI.create("https://api.github.com");
		}
		if (gitUrl == null) {
			gitUrl = URI.create("https://github.com");
		}
	}

	GitHubProperties(URI apiUrl, @Nullable URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
			@Nullable AppProperties mergeApp) {
		this(apiUrl, gitUrl, organization, platformApp, mergeApp, null);
	}

}
