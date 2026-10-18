package vn.vibefix.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Spring Security's authorized-client stores, made to keep nothing (SG-9): the only token vibefix
 * keeps is a Customer's, encrypted by {@code identity} itself.
 */
final class NoStoreAuthorizedClients {

	private NoStoreAuthorizedClients() {
	}

	static final class Service implements OAuth2AuthorizedClientService {

		@Override
		public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(String registrationId,
				String principalName) {
			return null;
		}

		@Override
		public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
		}

		@Override
		public void removeAuthorizedClient(String registrationId, String principalName) {
		}

	}

	static final class Repository implements OAuth2AuthorizedClientRepository {

		@Override
		public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(String registrationId,
				Authentication principal, HttpServletRequest request) {
			return null;
		}

		@Override
		public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
				HttpServletRequest request, HttpServletResponse response) {
		}

		@Override
		public void removeAuthorizedClient(String registrationId, Authentication principal, HttpServletRequest request,
				HttpServletResponse response) {
		}

	}

}
