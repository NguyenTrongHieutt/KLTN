package vn.vibefix.github.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
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
 * @param exchange the Actions token exchange (AD-44, Story 23.4)
 */
@ConfigurationProperties("vibefix.github")
record GitHubProperties(URI apiUrl, URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
		@Nullable AppProperties mergeApp, @Nullable URI webhookUrl, @Nullable String runnerRepository, Exchange exchange) {

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

	/**
	 * {@code vibefix.github.exchange.*}: the Actions token exchange (AD-44, AD-45, Story 23.4).
	 * @param acceptedBlobs the workflow files whose runs may exchange their ID token: each one's path in
	 * {@code .github/workflows/}, its blob SHA ({@code git hash-object} with CRLF read as LF), and an optional
	 * last day (Vietnam time) after which it is no longer accepted
	 * @param publicUrl the dev stack's cloudflared quick tunnel ({@code https://…}, an origin), which a dispatch
	 * names as {@code exchange_url}; the {@code dev} profile only, and {@code prod} refuses to start with it
	 * @param devPort the port the dev stack also serves the exchange on, alone, while {@code publicUrl} is
	 * set: the tunnel points there ({@value #DEFAULT_DEV_PORT} by default, 0 for none)
	 * @param issuer whose ID tokens the exchange accepts: GitHub Actions', unless a test names a stand-in
	 * ({@code prod} refuses any other); its key set is {@code <issuer>/.well-known/jwks}
	 */
	record Exchange(List<AcceptedBlob> acceptedBlobs, @Nullable String publicUrl, int devPort, String issuer) {

		static final String GITHUB_ISSUER = "https://token.actions.githubusercontent.com";

		static final int DEFAULT_DEV_PORT = 8098;

		/** An exchange host, as the workflows' {@code url_re} allows it. */
		private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");

		static final Exchange NONE = new Exchange(null, null, null, null);

		Exchange {
			acceptedBlobs = List.copyOf(acceptedBlobs);
			if (devPort < 0 || devPort > 65_535) {
				throw new IllegalArgumentException("vibefix.github.exchange.dev-port is a port, or 0 for none");
			}
			URI parsed = URI.create(issuer);
			boolean loopback = "http".equals(parsed.getScheme())
					&& ("127.0.0.1".equals(parsed.getHost()) || "localhost".equals(parsed.getHost()));
			if (parsed.getHost() == null || !("https".equals(parsed.getScheme()) || loopback)) {
				throw new IllegalArgumentException("vibefix.github.exchange.issuer is an https URL");
			}
		}

		@ConstructorBinding
		Exchange(@Nullable List<AcceptedBlob> acceptedBlobs, @Nullable String publicUrl, @Nullable Integer devPort,
				@Nullable String issuer) {
			this(acceptedBlobs == null ? List.of() : acceptedBlobs, origin(publicUrl),
					devPort == null ? DEFAULT_DEV_PORT : devPort,
					// Without its trailing slashes, which EnvironmentGuard accepts and no token's iss carries.
				issuer == null || issuer.isBlank() ? GITHUB_ISSUER : issuer.strip().replaceAll("/+$", ""));
		}

		/** Where the issuer publishes its signing keys. */
		URI jwks() {
			return URI.create(this.issuer.replaceAll("/+$", "") + "/.well-known/jwks");
		}

		/**
		 * The tunnel's origin, {@code https://host[:port]}, as the workflows append the exchange's path to it;
		 * {@code null} for none (blank).
		 * @throws IllegalArgumentException for anything but an https origin
		 */
		static @Nullable String origin(@Nullable String value) {
			if (value == null || value.isBlank()) {
				return null;
			}
			URI uri;
			try {
				uri = new URI(value.strip());
			}
			catch (URISyntaxException ex) {
				throw new IllegalArgumentException("vibefix.github.exchange.public-url is an https origin");
			}
			String path = uri.getRawPath();
			if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOST.matcher(uri.getHost()).matches()
					|| uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
					|| (path != null && !path.isEmpty() && !path.equals("/"))) {
				throw new IllegalArgumentException("vibefix.github.exchange.public-url is an https origin, without a path");
			}
			return "https://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
		}

	}

	/**
	 * One accepted workflow file (AD-45).
	 * @param path its path in the repository, for example {@code .github/workflows/vibefix.yml}
	 * @param blob its blob SHA, with CRLF read as LF
	 * @param endsOn its last accepted day, Vietnam time; none for no end
	 */
	record AcceptedBlob(String path, String blob, @Nullable LocalDate endsOn) {

		private static final Pattern PATH = Pattern.compile("\\.github/workflows/[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.ya?ml");

		private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

		AcceptedBlob {
			if (path == null || !PATH.matcher(path).matches()) {
				throw new IllegalArgumentException("An accepted blob's path is a file in .github/workflows/");
			}
			if (blob == null || !SHA.matcher(blob).matches()) {
				throw new IllegalArgumentException("An accepted blob is a 40-hex blob SHA");
			}
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
		if (exchange == null) {
			exchange = Exchange.NONE;
		}
	}

	GitHubProperties(URI apiUrl, @Nullable URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
			@Nullable AppProperties mergeApp, @Nullable URI webhookUrl, @Nullable String runnerRepository) {
		this(apiUrl, gitUrl, organization, platformApp, mergeApp, webhookUrl, runnerRepository, null);
	}

	GitHubProperties(URI apiUrl, @Nullable URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
			@Nullable AppProperties mergeApp, @Nullable URI webhookUrl) {
		this(apiUrl, gitUrl, organization, platformApp, mergeApp, webhookUrl, null, null);
	}

	GitHubProperties(URI apiUrl, @Nullable URI gitUrl, @Nullable String organization, @Nullable AppProperties platformApp,
			@Nullable AppProperties mergeApp) {
		this(apiUrl, gitUrl, organization, platformApp, mergeApp, null, null, null);
	}

}
