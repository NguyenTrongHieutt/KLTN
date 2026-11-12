package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.CustomerRepositories;
import vn.vibefix.shared.Kind;

/**
 * {@link CustomerRepositories} from the account row, with no GitHub call (AD-10, AD-13). Since Story 23.5b no path reads
 * with the Customer's kept linking token: the contributor check reads through the Source App, and the token itself is
 * retired by Story 23.5d.
 */
@Component
class TokenCustomerRepositories implements CustomerRepositories {

	private final Accounts accounts;

	TokenCustomerRepositories(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public Optional<Long> githubUserId(UUID accountId) {
		return this.accounts.findById(accountId)
			.filter(found -> found.kind() == Kind.USER && found.erasedAt() == null)
			.map(Account::githubUserId);
	}

}
