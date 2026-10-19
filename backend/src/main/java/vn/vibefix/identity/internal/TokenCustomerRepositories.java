package vn.vibefix.identity.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.github.GitHubRepositories;
import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;
import vn.vibefix.shared.Role;

/**
 * {@link CustomerRepositories} with the Customer's kept sign-in token (AD-10, AD-13, AD-22): opened in
 * memory for the call, sealed with the account ID as associated data, never returned or logged.
 */
@Component
class TokenCustomerRepositories implements CustomerRepositories {

	private final Accounts accounts;

	private final TokenCipher cipher;

	private final GitHubRepositories repositories;

	TokenCustomerRepositories(Accounts accounts, TokenCipher cipher, GitHubRepositories repositories) {
		this.accounts = accounts;
		this.cipher = cipher;
		this.repositories = repositories;
	}

	@Override
	public CustomerView read(UUID customerId, String owner, String name) {
		Account account = customer(customerId);
		return new CustomerView(account.githubUserId(), this.repositories.read(token(customerId), owner, name));
	}

	@Override
	public List<String> languages(UUID customerId, String owner, String name) {
		customer(customerId);
		return this.repositories.languages(token(customerId), owner, name);
	}

	private Account customer(UUID customerId) {
		return this.accounts.findById(customerId)
			.filter(found -> found.role() == Role.CUSTOMER && found.githubUserId() != null && !found.isLocked()
					&& found.erasedAt() == null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	/** The kept token, opened in memory for one call. */
	private String token(UUID customerId) {
		TokenCipher.Sealed sealed = this.accounts.customerToken(customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return this.cipher.open(sealed, customerId.toString());
	}

}
