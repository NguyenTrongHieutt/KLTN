package vn.vibefix.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import vn.vibefix.identity.GitHubGate;
import vn.vibefix.shared.ApiException;
import vn.vibefix.shared.ErrorCode;

/** {@link GitHubGate} on the account row, read again on every call (AD-19). */
@Component
class GitHubGates implements GitHubGate {

	private final Accounts accounts;

	GitHubGates(Accounts accounts) {
		this.accounts = accounts;
	}

	@Override
	public void requireGitHubLinked(UUID accountId) {
		Account account = this.accounts.findById(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED));
		if (!account.isActive()) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.ACCOUNT_LOCKED);
		}
		if (account.githubUserId() == null) {
			throw new ApiException(ErrorCode.FORBIDDEN, IdentityReason.GITHUB_NOT_LINKED);
		}
	}

}
