package vn.vibefix.github.internal;

import java.net.URI;
import java.nio.file.Path;
import java.util.regex.Pattern;

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
 * @param runnerRepository the one repository of the organization that may run GitHub Actions, by name
 * (AD-46, D5): required since 2026-11-12. AD-11's read-back wants the organization's Actions on that
 * repository only, and the runner private, holding only the published copy workflow, with no
 * collaborator. Unset (or blank), it is an organization difference: the sweep alerts it, and new
 * setups wait until it is set
 */
@ConfigurationProperties("vibefix.github")
record GitHubProperties(URI apiUrl, URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
		@Nullable AppProperties mergeApp, @Nullable URI webhookUrl, @Nullable String runnerRepository) {

	/** A repository name, as GitHub allows it inside an organization. */
	private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

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
		if (runnerRepository != null && runnerRepository.isBlank()) {
			// deploy/compose.yaml passes an empty value when no runner is set: unset, which the read-back reports.
			runnerRepository = null;
		}
		if (runnerRepository != null && (!REPOSITORY_NAME.matcher(runnerRepository).matches() || runnerRepository.equals(".")
				|| runnerRepository.equals(".."))) {
			throw new IllegalArgumentException(
					"vibefix.github.runner-repository is a repository name inside the organization, without its owner");
		}
	}

	GitHubProperties(URI apiUrl, @Nullable URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
			@Nullable AppProperties mergeApp, @Nullable URI webhookUrl) {
		this(apiUrl, gitUrl, organization, platformApp, mergeApp, webhookUrl, null);
	}

	GitHubProperties(URI apiUrl, @Nullable URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
			@Nullable AppProperties mergeApp) {
		this(apiUrl, gitUrl, organization, platformApp, mergeApp, null, null);
	}

}
