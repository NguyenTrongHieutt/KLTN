package vn.vibefix.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.GitHubAccountIds;
import vn.vibefix.shared.Kind;

/** {@link GitHubAccountIds} from the account row, with no GitHub call (AD-10, AD-13). */
@Component
class GitHubAccountIdsImpl implements GitHubAccountIds {

	private final Accounts accounts;

	GitHubAccountIdsImpl(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public Optional<Long> githubUserId(UUID accountId) {
		return this.accounts.findById(accountId)
			.filter(found -> found.kind() == Kind.USER && found.erasedAt() == null)
			.map(Account::githubUserId);
	}

}
