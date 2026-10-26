package vn.vibefix.identity.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub's side of linking (AD-10, FR-80): the authorization code is exchanged for a token with its
 * PKCE verifier (RFC 7636), and the token reads the user ({@code read:user}) and their email addresses
 * ({@code user:email}). Every call runs outside a transaction and gives up after 5 seconds. Nothing it
 * reads, and neither the code nor the token, is ever logged: a failure logs its exception class only.
 */
@Component
class GitHubLinkReader {

	/** Who GitHub says signed in there. */
	record LinkedUser(long id, String login, @Nullable String avatarUrl, @Nullable String primaryEmail,
			List<String> verifiedEmails) {

		LinkedUser {
			verifiedEmails = List.copyOf(verifiedEmails);
		}

	}

	/** The token GitHub issued and the user it reads. The token never leaves {@code identity}. */
	record Answer(String token, LinkedUser user) {

		@Override
		public String toString() {
			return "Answer[user=" + this.user.id() + "]";
		}

	}

	private static final Logger log = LoggerFactory.getLogger(GitHubLinkReader.class);

	private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {
	};

	private static final ParameterizedTypeReference<List<Map<String, Object>>> EMAILS = new ParameterizedTypeReference<>() {
	};

	private final GitHubSignIn signIn;

	private final RestClient http;

	GitHubLinkReader(GitHubSignIn signIn) {
		this.signIn = signIn;
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requests.setReadTimeout(Duration.ofSeconds(5));
		this.http = RestClient.builder().requestFactory(requests).build();
	}

	/**
	 * Exchanges the code at the registration's token URI, then reads the user and their emails.
	 * @param redirectUri the {@code redirect_uri} the authorization request named
	 * @return empty when this environment has no linking OAuth App, or when GitHub refused or did not
	 * answer; the user's emails are empty when only their read failed
	 */
	Optional<Answer> read(String code, String redirectUri, String codeVerifier) {
		ClientRegistration registration = this.signIn.registration().orElse(null);
		if (registration == null) {
			return Optional.empty();
		}
		try {
			MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
			form.add("client_id", registration.getClientId());
			form.add("client_secret", registration.getClientSecret());
			form.add("code", code);
			form.add("redirect_uri", redirectUri);
			form.add("code_verifier", codeVerifier);
			Map<String, Object> issued = this.http.post()
				.uri(registration.getProviderDetails().getTokenUri())
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(form)
				.retrieve()
				.body(OBJECT);
			if (issued == null || !(issued.get("access_token") instanceof String token) || token.isBlank()) {
				// GitHub answers a refused exchange with 200 and an error member.
				log.warn("GitHub issued no token for a link");
				return Optional.empty();
			}
			Map<String, Object> user = this.http.get()
				.uri(registration.getProviderDetails().getUserInfoEndpoint().getUri())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
				.retrieve()
				.body(OBJECT);
			if (user == null || !(user.get("id") instanceof Number id) || !(user.get("login") instanceof String login)
					|| login.isBlank()) {
				log.warn("GitHub answered a link without a user");
				return Optional.empty();
			}
			List<Map<String, Object>> emails = emails(token);
			String avatar = user.get("avatar_url") instanceof String url && !url.isBlank() ? url : null;
			return Optional.of(new Answer(token, new LinkedUser(id.longValue(), login, avatar,
					primaryVerifiedEmail(emails).orElse(null), verifiedEmails(emails))));
		}
		catch (RestClientException ex) {
			// The exception class only: a message could hold the code, the token, or a claim.
			log.warn("A GitHub link could not be completed: {}", ex.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** Every email address GitHub lists for the token; none when it does not answer (FR-48). */
	private List<Map<String, Object>> emails(String token) {
		String uri = this.signIn.userEmailsUri();
		if (uri == null || uri.isBlank()) {
			return List.of();
		}
		try {
			List<Map<String, Object>> emails = this.http.get()
				.uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
				.retrieve()
				.body(EMAILS);
			return emails == null ? List.of() : emails;
		}
		catch (RestClientException ex) {
			// The exception class only: a message could hold the address.
			log.warn("Could not read the GitHub email addresses: {}", ex.getClass().getSimpleName());
			return List.of();
		}
	}

	/** The primary address, when GitHub verified it: where notifications went before Google sign-in. */
	static Optional<String> primaryVerifiedEmail(List<Map<String, Object>> emails) {
		return emails.stream()
			.filter(email -> Boolean.TRUE.equals(email.get("primary")) && Boolean.TRUE.equals(email.get("verified")))
			.map(email -> email.get("email"))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.findFirst();
	}

	/** Every address GitHub verified, each a person-link signal (AD-19). */
	static List<String> verifiedEmails(List<Map<String, Object>> emails) {
		return emails.stream()
			.filter(email -> Boolean.TRUE.equals(email.get("verified")))
			.map(email -> email.get("email"))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.distinct()
			.toList();
	}

}
