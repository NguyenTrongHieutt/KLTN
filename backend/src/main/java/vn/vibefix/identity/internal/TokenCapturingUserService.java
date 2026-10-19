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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads the GitHub user as Spring does, and hands the access token to the sign-in handler through a
 * request attribute: it never enters the principal, so it never reaches the session, and no
 * authorized-client store keeps it (SG-9).
 * <p>
 * With the same token it reads the user's email addresses through the {@code user:email} scope: the
 * primary verified one is where notifications are emailed (FR-48, AD-27), and every verified one is a
 * person-link signal (AD-19). When GitHub does not answer, the sign-in goes on and the address on
 * file stays.
 */
class TokenCapturingUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	static final String TOKEN_ATTRIBUTE = TokenCapturingUserService.class.getName() + ".token";

	/** The primary verified email, present only when GitHub answered and has one. */
	static final String EMAIL_ATTRIBUTE = TokenCapturingUserService.class.getName() + ".email";

	/** Every verified email, an empty list when GitHub did not answer. */
	static final String VERIFIED_EMAILS_ATTRIBUTE = TokenCapturingUserService.class.getName() + ".verifiedEmails";

	private static final Logger log = LoggerFactory.getLogger(TokenCapturingUserService.class);

	private static final ParameterizedTypeReference<List<Map<String, Object>>> EMAILS = new ParameterizedTypeReference<>() {
	};

	private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

	private final @Nullable String userEmailsUri;

	private final RestClient http;

	TokenCapturingUserService(@Nullable String userEmailsUri) {
		this(new DefaultOAuth2UserService(), userEmailsUri);
	}

	TokenCapturingUserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate, @Nullable String userEmailsUri) {
		this.delegate = delegate;
		this.userEmailsUri = userEmailsUri;
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requests.setReadTimeout(Duration.ofSeconds(10));
		this.http = RestClient.builder().requestFactory(requests).build();
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) {
		OAuth2User user = this.delegate.loadUser(request);
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes != null) {
			String token = request.getAccessToken().getTokenValue();
			attributes.setAttribute(TOKEN_ATTRIBUTE, token, RequestAttributes.SCOPE_REQUEST);
			List<Map<String, Object>> emails = emails(token);
			primaryVerifiedEmail(emails)
				.ifPresent(email -> attributes.setAttribute(EMAIL_ATTRIBUTE, email, RequestAttributes.SCOPE_REQUEST));
			attributes.setAttribute(VERIFIED_EMAILS_ATTRIBUTE, verifiedEmails(emails), RequestAttributes.SCOPE_REQUEST);
		}
		return user;
	}

	private static Optional<String> primaryVerifiedEmail(List<Map<String, Object>> emails) {
		return emails.stream()
			.filter(email -> Boolean.TRUE.equals(email.get("primary")) && Boolean.TRUE.equals(email.get("verified")))
			.map(email -> email.get("email"))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.findFirst();
	}

	private static List<String> verifiedEmails(List<Map<String, Object>> emails) {
		return emails.stream()
			.filter(email -> Boolean.TRUE.equals(email.get("verified")))
			.map(email -> email.get("email"))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.distinct()
			.toList();
	}

	private List<Map<String, Object>> emails(String token) {
		if (this.userEmailsUri == null || this.userEmailsUri.isBlank()) {
			return List.of();
		}
		try {
			List<Map<String, Object>> emails = this.http.get()
				.uri(this.userEmailsUri)
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

}
